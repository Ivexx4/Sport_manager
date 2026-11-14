package pt.voleibol.gestao.matches;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

@Service
class MatchService {

    private static final Set<String> MANAGERS = Set.of("ADMIN", "DIRECAO");
    private static final Set<String> MATCH_STATUSES = Set.of("SCHEDULED", "PLAYED", "CANCELLED", "POSTPONED");
    private static final Set<String> CALLUP_RESPONSES = Set.of("CONFIRMED", "DECLINED");

    record MatchView(UUID id, UUID teamId, String competition, String opponent, String location, UUID courtId,
                     Instant matchDate, boolean isHome, String status, Instant createdAt, Instant updatedAt) {
    }

    record SetScoreView(int setNumber, int ourPoints, int opponentPoints) {
    }

    record CallUpView(UUID athleteId, String fullName, String status, String response, Instant respondedAt) {
    }

    private final MatchRepository matches;
    private final JdbcClient jdbc;

    MatchService(MatchRepository matches, JdbcClient jdbc) {
        this.matches = matches;
        this.jdbc = jdbc;
    }

    // --- matches ---

    @Transactional(readOnly = true)
    List<MatchView> listMatches(UUID actorId, Set<String> roles, UUID teamId) {
        UUID tenantId = requireTenant();
        boolean allowed = isManager(roles) || roles.contains("TREINADOR")
                || roles.contains("ATLETA") || roles.contains("ENCARREGADO");
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (teamId != null) {
            requireVisibleTeam(tenantId, teamId, actorId, roles);
        }
        return matches.list(tenantId, teamId).stream()
                .filter(m -> isVisibleTeam(tenantId, m.teamId(), actorId, roles))
                .map(MatchService::matchView).toList();
    }

    @Transactional(readOnly = true)
    MatchView getMatch(UUID actorId, Set<String> roles, UUID id) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        return matchView(row);
    }

    @Transactional
    MatchView createMatch(UUID actorId, Set<String> roles, UUID teamId, String competition, String opponent,
                          String location, UUID courtId, Instant matchDate, Boolean isHome, String status) {
        UUID tenantId = requireTenant();
        requireVisibleTeam(tenantId, teamId, actorId, roles);
        requireWritable(roles);
        if (opponent == null || opponent.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adversário obrigatório");
        }
        if (matchDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data do jogo obrigatória");
        }
        if (courtId != null && !matches.venueExists(tenantId, courtId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local não encontrado");
        }
        UUID id = UUID.randomUUID();
        matches.insert(id, tenantId, teamId, competition, opponent.trim(), location, courtId, matchDate,
                isHome == null ? true : isHome, validStatus(status == null ? "SCHEDULED" : status), actorId);
        matches.audit(tenantId, actorId, "MATCH_CREATED", id);
        return matchView(matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound));
    }

    @Transactional
    MatchView updateMatch(UUID actorId, Set<String> roles, UUID id, UUID teamId, String competition, String opponent,
                          String location, UUID courtId, Instant matchDate, Boolean isHome, String status) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow current = matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound);
        UUID newTeam = teamId == null ? current.teamId() : teamId;
        requireVisibleTeam(tenantId, current.teamId(), actorId, roles);
        requireVisibleTeam(tenantId, newTeam, actorId, roles);
        requireWritable(roles);
        if (opponent == null || opponent.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adversário obrigatório");
        }
        if (matchDate == null) {
            matchDate = current.matchDate();
        }
        if (courtId != null && !matches.venueExists(tenantId, courtId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local não encontrado");
        }
        if (!matches.update(tenantId, id, newTeam, competition, opponent.trim(), location, courtId, matchDate,
                isHome == null ? current.isHome() : isHome,
                status == null || status.isBlank() ? current.status() : validStatus(status))) {
            throw matchNotFound();
        }
        matches.audit(tenantId, actorId, "MATCH_UPDATED", id);
        return matchView(matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound));
    }

    @Transactional
    void deleteMatch(UUID actorId, Set<String> roles, UUID id) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        requireWritable(roles);
        if (!matches.anonymize(tenantId, id)) {
            throw matchNotFound();
        }
        matches.audit(tenantId, actorId, "MATCH_ANONYMIZED", id);
    }

    // --- set scores ---

    @Transactional(readOnly = true)
    List<SetScoreView> listSetScores(UUID actorId, Set<String> roles, UUID matchId) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        return matches.listSetScores(tenantId, matchId).stream()
                .map(s -> new SetScoreView(s.setNumber(), s.ourPoints(), s.opponentPoints())).toList();
    }

    @Transactional
    void setSetScore(UUID actorId, Set<String> roles, UUID matchId, int setNumber, int ourPoints, int opponentPoints) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        requireWritable(roles);
        if (setNumber <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Número de set inválido");
        }
        matches.upsertSetScore(tenantId, matchId, setNumber, ourPoints, opponentPoints, actorId);
        matches.audit(tenantId, actorId, "MATCH_SET_SCORE_SET", matchId);
    }

    @Transactional
    void deleteSetScore(UUID actorId, Set<String> roles, UUID matchId, int setNumber) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        requireWritable(roles);
        if (!matches.deleteSetScore(tenantId, matchId, setNumber)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Set não encontrado");
        }
        matches.audit(tenantId, actorId, "MATCH_SET_SCORE_REMOVED", matchId);
    }

    // --- call ups ---

    @Transactional(readOnly = true)
    List<CallUpView> listCallUps(UUID actorId, Set<String> roles, UUID matchId) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        return matches.listCallUps(tenantId, matchId).stream()
                .map(c -> new CallUpView(c.athleteId(), c.fullName(), c.status(), c.response(), c.respondedAt()))
                .toList();
    }

    @Transactional
    void addCallUp(UUID actorId, Set<String> roles, UUID matchId, UUID athleteId) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        requireWritable(roles);
        if (!athleteOnTeam(tenantId, row.teamId(), athleteId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Atleta não pertence à equipa");
        }
        if (matches.callUpExists(tenantId, matchId, athleteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Atleta já convocado");
        }
        matches.insertCallUp(tenantId, matchId, athleteId, actorId);
        matches.audit(tenantId, actorId, "MATCH_CALLUP_ADDED", matchId);
    }

    @Transactional
    void removeCallUp(UUID actorId, Set<String> roles, UUID matchId, UUID athleteId) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        requireWritable(roles);
        if (!matches.deleteCallUp(tenantId, matchId, athleteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Convocatória não encontrada");
        }
        matches.audit(tenantId, actorId, "MATCH_CALLUP_REMOVED", matchId);
    }

    @Transactional
    void respondCallUp(UUID actorId, Set<String> roles, UUID matchId, UUID athleteId, String response) {
        UUID tenantId = requireTenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        // athlete can respond if on team roster or linked; allow any member? Require visible team or athlete self.
        // Allow if user is manager/trainer of team OR the athlete's linked user.
        boolean allowed = isManager(roles) || roles.contains("TREINADOR");
        if (!allowed) {
            // check if actor is the athlete
            // (best-effort: allow ATLETA role if athlete.user_id == actor)
            allowed = isAthleteSelf(tenantId, athleteId, actorId);
        }
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        // ensure callup exists
        if (!matches.callUpExists(tenantId, matchId, athleteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Convocatória não encontrada");
        }
        String resp = response == null ? null : response.trim().toUpperCase(java.util.Locale.ROOT);
        if (resp != null && !CALLUP_RESPONSES.contains(resp)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resposta inválida");
        }
        matches.updateCallUpResponse(tenantId, matchId, athleteId, resp, resp == null ? null : Instant.now());
        matches.audit(tenantId, actorId, "MATCH_CALLUP_RESPONDED", matchId);
    }

    // --- helpers ---

    private void requireWritable(Set<String> roles) {
        if (!isManager(roles) && !roles.contains("TREINADOR")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private void requireVisibleTeam(UUID tenantId, UUID teamId, UUID actorId, Set<String> roles) {
        if (isManager(roles)) {
            if (!teamExists(tenantId, teamId)) throw teamNotFound();
            return;
        }
        if (roles.contains("TREINADOR")) {
            if (!teamVisibleToStaff(tenantId, teamId, actorId)) throw teamNotFound();
            return;
        }
        // ATLETA/ENCARREGADO: visible if on team roster
        if (roles.contains("ATLETA") || roles.contains("ENCARREGADO")) {
            if (!teamHasAthleteLinked(tenantId, teamId, actorId)) throw teamNotFound();
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private boolean isVisibleTeam(UUID tenantId, UUID teamId, UUID actorId, Set<String> roles) {
        if (isManager(roles)) return teamExists(tenantId, teamId);
        if (roles.contains("TREINADOR")) return teamVisibleToStaff(tenantId, teamId, actorId);
        if (roles.contains("ATLETA") || roles.contains("ENCARREGADO")) return teamHasAthleteLinked(tenantId, teamId, actorId);
        return false;
    }

    private boolean teamExists(UUID tenantId, UUID teamId) {
        Boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM team WHERE tenant_id = :t AND id = :id AND anonymized_at IS NULL)")
                .param("t", tenantId).param("id", teamId).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    private boolean teamVisibleToStaff(UUID tenantId, UUID teamId, UUID actorId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM team t WHERE t.tenant_id = :t AND t.id = :id AND t.anonymized_at IS NULL
                          AND EXISTS (SELECT 1 FROM team_staff s WHERE s.tenant_id=t.tenant_id AND s.team_id=t.id AND s.user_id=:a))
                        """)
                .param("t", tenantId).param("id", teamId).param("a", actorId).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    private boolean teamHasAthleteLinked(UUID tenantId, UUID teamId, UUID actorId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM team_athlete ta JOIN athlete a ON a.tenant_id=ta.tenant_id AND a.id=ta.athlete_id
                          WHERE ta.tenant_id=:t AND ta.team_id=:team AND a.anonymized_at IS NULL
                            AND (a.user_id=:a OR EXISTS (
                                SELECT 1 FROM athlete_guardian g
                                WHERE g.tenant_id=a.tenant_id AND g.athlete_id=a.id AND g.user_id=:a)))
                        """)
                .param("t", tenantId).param("team", teamId).param("a", actorId).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    private boolean athleteOnTeam(UUID tenantId, UUID teamId, UUID athleteId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM team_athlete WHERE tenant_id=:t AND team_id=:team AND athlete_id=:a)
                        """)
                .param("t", tenantId).param("team", teamId).param("a", athleteId).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    private boolean isAthleteSelf(UUID tenantId, UUID athleteId, UUID actorId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM athlete WHERE tenant_id=:t AND id=:a AND user_id=:u AND anonymized_at IS NULL)
                        """)
                .param("t", tenantId).param("a", athleteId).param("u", actorId).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    private static boolean isManager(Set<String> roles) {
        return roles.stream().anyMatch(MANAGERS::contains);
    }

    private static String validStatus(String status) {
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!MATCH_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado inválido");
        }
        return normalized;
    }

    private static UUID requireTenant() {
        return TenantContext.current().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube: usa o subdomínio do clube"));
    }

    private static ResponseStatusException matchNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Jogo não encontrado");
    }

    private static ResponseStatusException teamNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipa não encontrada");
    }

    private static MatchView matchView(MatchRepository.MatchRow row) {
        return new MatchView(row.id(), row.teamId(), row.competition(), row.opponent(), row.location(),
                row.courtId(), row.matchDate(), row.isHome(), row.status(), row.createdAt(), row.updatedAt());
    }
}