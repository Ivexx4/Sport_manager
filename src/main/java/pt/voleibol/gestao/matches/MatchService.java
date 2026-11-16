package pt.voleibol.gestao.matches;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.notifications.EmailSender;
import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Db;
import pt.voleibol.gestao.shared.Role;
import pt.voleibol.gestao.shared.Rules;
import pt.voleibol.gestao.teams.TeamAccess;

@Service
class MatchService {

    private static final ZoneId LISBOA = ZoneId.of("Europe/Lisbon");
    private static final DateTimeFormatter MATCH_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    record MatchView(UUID id, UUID teamId, String competition, String opponent, String location, UUID courtId,
                     Instant matchDate, boolean isHome, String status, Instant createdAt, Instant updatedAt) {
    }

    record SetScoreView(int setNumber, int ourPoints, int opponentPoints) {
    }

    record CallUpView(UUID athleteId, String fullName, String status, String response, Instant respondedAt) {
    }

    private final MatchRepository matches;
    private final JdbcClient jdbc;
    private final TeamAccess teamAccess;
    private final EmailSender emailSender;

    MatchService(MatchRepository matches, JdbcClient jdbc, TeamAccess teamAccess, EmailSender emailSender) {
        this.matches = matches;
        this.jdbc = jdbc;
        this.teamAccess = teamAccess;
        this.emailSender = emailSender;
    }

    // --- matches ---

    @Transactional(readOnly = true)
    List<MatchView> listMatches(CurrentUser user, UUID teamId) {
        UUID tenantId = Rules.tenant();
        if (!user.isSport()) throw Rules.forbidden();
        if (teamId != null) teamAccess.requireVisible(tenantId, teamId, user);
        return matches.list(tenantId, teamId).stream()
                .filter(m -> teamAccess.visible(tenantId, m.teamId(), user))
                .map(MatchService::matchView).toList();
    }

    @Transactional(readOnly = true)
    MatchView getMatch(CurrentUser user, UUID id) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        return matchView(row);
    }

    @Transactional
    MatchView createMatch(CurrentUser user, UUID teamId, String competition, String opponent,
                          String location, UUID courtId, Instant matchDate, Boolean isHome, String status) {
        UUID tenantId = Rules.tenant();
        teamAccess.requireVisible(tenantId, teamId, user);
        Rules.requireWritable(user);
        if (opponent == null || opponent.isBlank()) throw Rules.badRequest("Adversário obrigatório");
        if (matchDate == null) throw Rules.badRequest("Data do jogo obrigatória");
        if (courtId != null && !matches.venueExists(tenantId, courtId)) throw Rules.notFound("Local não encontrado");
        UUID id = UUID.randomUUID();
        matches.insert(id, tenantId, teamId, competition, opponent.trim(), location, courtId, matchDate,
                isHome == null ? true : isHome,
                Rules.parse(MatchStatus.class, status, "Estado", MatchStatus.DEFAULT).name(), user.id());
        matches.audit(tenantId, user.id(), "MATCH_CREATED", id);
        return matchView(matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound));
    }

    @Transactional
    MatchView updateMatch(CurrentUser user, UUID id, UUID teamId, String competition, String opponent,
                          String location, UUID courtId, Instant matchDate, Boolean isHome, String status) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow current = matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound);
        UUID newTeam = teamId == null ? current.teamId() : teamId;
        teamAccess.requireVisible(tenantId, current.teamId(), user);
        teamAccess.requireVisible(tenantId, newTeam, user);
        Rules.requireWritable(user);
        if (opponent == null || opponent.isBlank()) throw Rules.badRequest("Adversário obrigatório");
        if (matchDate == null) matchDate = current.matchDate();
        if (courtId != null && !matches.venueExists(tenantId, courtId)) throw Rules.notFound("Local não encontrado");
        if (!matches.update(tenantId, id, newTeam, competition, opponent.trim(), location, courtId, matchDate,
                isHome == null ? current.isHome() : isHome,
                status == null || status.isBlank() ? current.status()
                        : Rules.parse(MatchStatus.class, status, "Estado").name())) {
            throw matchNotFound();
        }
        matches.audit(tenantId, user.id(), "MATCH_UPDATED", id);
        return matchView(matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound));
    }

    @Transactional
    void deleteMatch(CurrentUser user, UUID id) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, id).orElseThrow(MatchService::matchNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        Rules.requireWritable(user);
        if (!matches.anonymize(tenantId, id)) throw matchNotFound();
        matches.audit(tenantId, user.id(), "MATCH_ANONYMIZED", id);
    }

    // --- set scores ---

    @Transactional(readOnly = true)
    List<SetScoreView> listSetScores(CurrentUser user, UUID matchId) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        return matches.listSetScores(tenantId, matchId).stream()
                .map(s -> new SetScoreView(s.setNumber(), s.ourPoints(), s.opponentPoints())).toList();
    }

    @Transactional
    void setSetScore(CurrentUser user, UUID matchId, int setNumber, int ourPoints, int opponentPoints) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        Rules.requireWritable(user);
        if (setNumber <= 0) throw Rules.badRequest("Número de set inválido");
        matches.upsertSetScore(tenantId, matchId, setNumber, ourPoints, opponentPoints, user.id());
        matches.audit(tenantId, user.id(), "MATCH_SET_SCORE_SET", matchId);
    }

    @Transactional
    void deleteSetScore(CurrentUser user, UUID matchId, int setNumber) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        Rules.requireWritable(user);
        if (!matches.deleteSetScore(tenantId, matchId, setNumber)) throw Rules.notFound("Set não encontrado");
        matches.audit(tenantId, user.id(), "MATCH_SET_SCORE_REMOVED", matchId);
    }

    // --- call ups ---

    @Transactional(readOnly = true)
    List<CallUpView> listCallUps(CurrentUser user, UUID matchId) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        return matches.listCallUps(tenantId, matchId).stream()
                .map(c -> new CallUpView(c.athleteId(), c.fullName(), c.status(), c.response(), c.respondedAt()))
                .toList();
    }

    @Transactional
    void addCallUp(CurrentUser user, UUID matchId, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        Rules.requireWritable(user);
        if (!teamAccess.athleteOnTeam(tenantId, row.teamId(), athleteId)) throw Rules.badRequest("Atleta não pertence à equipa");
        if (matches.callUpExists(tenantId, matchId, athleteId)) throw Rules.conflict("Atleta já convocado");
        matches.insertCallUp(tenantId, matchId, athleteId, user.id());
        matches.audit(tenantId, user.id(), "MATCH_CALLUP_ADDED", matchId);
        notifyCallUp(tenantId, row, athleteId);
    }

    @Transactional
    void removeCallUp(CurrentUser user, UUID matchId, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        Rules.requireWritable(user);
        if (!matches.deleteCallUp(tenantId, matchId, athleteId)) throw Rules.notFound("Convocatória não encontrada");
        matches.audit(tenantId, user.id(), "MATCH_CALLUP_REMOVED", matchId);
    }

    @Transactional
    void respondCallUp(CurrentUser user, UUID matchId, UUID athleteId, String response) {
        UUID tenantId = Rules.tenant();
        MatchRepository.MatchRow row = matches.findById(tenantId, matchId).orElseThrow(MatchService::matchNotFound);
        // Gestores/treinadores da equipa ou o próprio atleta (ligado ao utilizador) podem responder.
        if (!user.isManager() && !user.has(Role.TREINADOR)
                && !isAthleteSelf(tenantId, athleteId, user.id())) throw Rules.forbidden();
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        if (!matches.callUpExists(tenantId, matchId, athleteId)) throw Rules.notFound("Convocatória não encontrada");
        String resp = response == null ? null : response.trim().toUpperCase(java.util.Locale.ROOT);
        if (resp != null && Rules.find(CallUpResponse.class, resp).isEmpty()) throw Rules.badRequest("Resposta inválida");
        matches.updateCallUpResponse(tenantId, matchId, athleteId, resp, resp == null ? null : Instant.now());
        matches.audit(tenantId, user.id(), "MATCH_CALLUP_RESPONDED", matchId);
    }

    // --- helpers ---

    /**
     * Envia a convocatória por e-mail (outbox) para o atleta e encarregados com e-mail.
     * Os destinatários são deduplicados e os registos sem contacto são ignorados.
     */
    private void notifyCallUp(UUID tenantId, MatchRepository.MatchRow match, UUID athleteId) {
        String athleteName = jdbc.sql("""
                        SELECT full_name FROM athlete
                        WHERE tenant_id = :t AND id = :a AND anonymized_at IS NULL
                        """)
                .param("t", tenantId).param("a", athleteId).query(String.class).optional().orElse(null);
        if (athleteName == null) return;

        Set<String> recipients = new LinkedHashSet<>();
        jdbc.sql("""
                        SELECT email FROM athlete
                        WHERE tenant_id = :t AND id = :a AND anonymized_at IS NULL AND email IS NOT NULL
                        """)
                .param("t", tenantId).param("a", athleteId)
                .query(String.class).optional().ifPresent(email -> addRecipient(recipients, email));
        jdbc.sql("""
                        SELECT u.email FROM athlete_guardian g
                        JOIN app_user u ON u.id = g.user_id
                        WHERE g.tenant_id = :t AND g.athlete_id = :a AND u.status = 'ACTIVE'
                        """)
                .param("t", tenantId).param("a", athleteId)
                .query(String.class).list().forEach(email -> addRecipient(recipients, email));
        if (recipients.isEmpty()) return;

        String teamName = jdbc.sql("SELECT name FROM team WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId).param("id", match.teamId())
                .query(String.class).optional().orElse("equipa");
        String quando = MATCH_DATE_FORMAT.format(LocalDateTime.ofInstant(match.matchDate(), LISBOA));
        String local = match.isHome() ? "Casa" : "Fora";
        if (match.location() != null && !match.location().isBlank()) local += " (" + match.location() + ")";
        String competicao = match.competition() == null || match.competition().isBlank()
                ? "" : "\nCompetição: " + match.competition();
        String subject = "Convocatória: " + teamName + " vs " + match.opponent();
        String body = """
                O atleta %s foi convocado para o jogo %s vs %s.
                Data: %s
                Local: %s%s

                Confirma ou recusa a convocatória na plataforma.
                """.formatted(athleteName, teamName, match.opponent(), quando, local, competicao);
        for (String recipient : recipients) {
            emailSender.send(recipient, subject, body);
        }
    }

    private static void addRecipient(Set<String> recipients, String email) {
        if (email == null) return;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) recipients.add(normalized);
    }

    private boolean isAthleteSelf(UUID tenantId, UUID athleteId, UUID actorId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM athlete WHERE tenant_id=:t AND id=:a AND user_id=:u AND anonymized_at IS NULL)
                        """)
                .param("t", tenantId).param("a", athleteId).param("u", actorId));
    }

    private static ResponseStatusException matchNotFound() {
        return Rules.notFound("Jogo não encontrado");
    }

    private static MatchView matchView(MatchRepository.MatchRow row) {
        return new MatchView(row.id(), row.teamId(), row.competition(), row.opponent(), row.location(),
                row.courtId(), row.matchDate(), row.isHome(), row.status(), row.createdAt(), row.updatedAt());
    }
}