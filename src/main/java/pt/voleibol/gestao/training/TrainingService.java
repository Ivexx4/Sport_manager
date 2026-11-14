package pt.voleibol.gestao.training;

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
class TrainingService {

    private static final Set<String> MANAGERS = Set.of("ADMIN", "DIRECAO");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "CANCELLED", "COMPLETED");
    private static final Set<String> ATTENDANCE_STATUSES = Set.of("PRESENT", "ABSENT", "JUSTIFIED", "LATE");

    record VenueView(UUID id, String name, String address, Instant createdAt, Instant updatedAt) {
    }

    record SessionView(UUID id, UUID teamId, UUID courtId, Instant startTime, Instant endTime,
                      String recurrence, String notes, String status, Instant createdAt, Instant updatedAt) {
    }

    record AttendanceView(UUID athleteId, String fullName, String status, String justification) {
    }

    private final VenueRepository venues;
    private final TrainingSessionRepository sessions;
    private final JdbcClient jdbc;

    TrainingService(VenueRepository venues, TrainingSessionRepository sessions, JdbcClient jdbc) {
        this.venues = venues;
        this.sessions = sessions;
        this.jdbc = jdbc;
    }

    // --- venues ---

    @Transactional(readOnly = true)
    List<VenueView> listVenues(UUID actorId, Set<String> roles) {
        requireMember(roles);
        return venues.list(requireTenant()).stream()
                .map(v -> new VenueView(v.id(), v.name(), v.address(), v.createdAt(), v.updatedAt())).toList();
    }

    @Transactional(readOnly = true)
    VenueView getVenue(UUID actorId, Set<String> roles, UUID id) {
        requireMember(roles);
        return venueView(venues.findById(requireTenant(), id).orElseThrow(TrainingService::venueNotFound));
    }

    @Transactional
    VenueView createVenue(UUID actorId, Set<String> roles, String name, String address) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        String normalized = requiredText(name);
        if (venues.nameExists(tenantId, normalized, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um local com este nome");
        }
        UUID id = UUID.randomUUID();
        venues.insert(id, tenantId, normalized, address, actorId);
        venues.audit(tenantId, actorId, "VENUE_CREATED", id);
        return venueView(venues.findById(tenantId, id).orElseThrow(TrainingService::venueNotFound));
    }

    @Transactional
    VenueView updateVenue(UUID actorId, Set<String> roles, UUID id, String name, String address) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        venues.findById(tenantId, id).orElseThrow(TrainingService::venueNotFound);
        String normalized = requiredText(name);
        if (venues.nameExists(tenantId, normalized, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um local com este nome");
        }
        if (!venues.update(tenantId, id, normalized, address)) {
            throw venueNotFound();
        }
        venues.audit(tenantId, actorId, "VENUE_UPDATED", id);
        return venueView(venues.findById(tenantId, id).orElseThrow(TrainingService::venueNotFound));
    }

    @Transactional
    void deleteVenue(UUID actorId, Set<String> roles, UUID id) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        if (!venues.anonymize(tenantId, id)) {
            throw venueNotFound();
        }
        venues.audit(tenantId, actorId, "VENUE_ANONYMIZED", id);
    }

    // --- sessions ---

    @Transactional(readOnly = true)
    List<SessionView> listSessions(UUID actorId, Set<String> roles, UUID teamId) {
        UUID tenantId = requireTenant();
        boolean writer = isManager(roles) || roles.contains("TREINADOR");
        boolean athleteOrGuardian = roles.contains("ATLETA") || roles.contains("ENCARREGADO");
        if (!writer && !athleteOrGuardian) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (teamId != null) {
            requireVisibleTeam(tenantId, teamId, actorId, roles);
        }
        return sessions.list(tenantId, teamId).stream()
                .filter(s -> isVisibleTeam(tenantId, s.teamId(), actorId, roles))
                .map(TrainingService::sessionView).toList();
    }

    @Transactional(readOnly = true)
    SessionView getSession(UUID actorId, Set<String> roles, UUID id) {
        UUID tenantId = requireTenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, id)
                .orElseThrow(TrainingService::sessionNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        return sessionView(row);
    }

    @Transactional
    SessionView createSession(UUID actorId, Set<String> roles, UUID teamId, UUID courtId,
                             Instant startTime, Instant endTime, String recurrence, String notes, String status) {
        UUID tenantId = requireTenant();
        requireVisibleTeam(tenantId, teamId, actorId, roles);
        requireWritable(roles);
        if (courtId != null && venues.findById(tenantId, courtId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local não encontrado");
        }
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Intervalo de tempo inválido");
        }
        UUID id = UUID.randomUUID();
        sessions.insert(id, tenantId, teamId, courtId, startTime, endTime, recurrence, notes,
                validStatus(status == null ? "ACTIVE" : status), actorId);
        sessions.audit(tenantId, actorId, "TRAINING_SESSION_CREATED", id);
        return sessionView(sessions.findById(tenantId, id).orElseThrow(TrainingService::sessionNotFound));
    }

    @Transactional
    SessionView updateSession(UUID actorId, Set<String> roles, UUID id, UUID teamId, UUID courtId,
                             Instant startTime, Instant endTime, String recurrence, String notes, String status) {
        UUID tenantId = requireTenant();
        TrainingSessionRepository.SessionRow current = sessions.findById(tenantId, id)
                .orElseThrow(TrainingService::sessionNotFound);
        UUID newTeam = teamId == null ? current.teamId() : teamId;
        requireVisibleTeam(tenantId, current.teamId(), actorId, roles);
        requireVisibleTeam(tenantId, newTeam, actorId, roles);
        requireWritable(roles);
        if (courtId != null && venues.findById(tenantId, courtId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local não encontrado");
        }
        Instant s = startTime == null ? current.startTime() : startTime;
        Instant e = endTime == null ? current.endTime() : endTime;
        if (!e.isAfter(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Intervalo de tempo inválido");
        }
        if (!sessions.update(tenantId, id, newTeam, courtId, s, e, recurrence, notes,
                status == null || status.isBlank() ? current.status() : validStatus(status))) {
            throw sessionNotFound();
        }
        sessions.audit(tenantId, actorId, "TRAINING_SESSION_UPDATED", id);
        return sessionView(sessions.findById(tenantId, id).orElseThrow(TrainingService::sessionNotFound));
    }

    @Transactional
    void deleteSession(UUID actorId, Set<String> roles, UUID id) {
        UUID tenantId = requireTenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, id)
                .orElseThrow(TrainingService::sessionNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        requireWritable(roles);
        if (!sessions.anonymize(tenantId, id)) {
            throw sessionNotFound();
        }
        sessions.audit(tenantId, actorId, "TRAINING_SESSION_ANONYMIZED", id);
    }

    // --- attendance ---

    @Transactional(readOnly = true)
    List<AttendanceView> listAttendance(UUID actorId, Set<String> roles, UUID sessionId) {
        UUID tenantId = requireTenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, sessionId)
                .orElseThrow(TrainingService::sessionNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        return sessions.listAttendance(tenantId, sessionId).stream()
                .map(a -> new AttendanceView(a.athleteId(), a.fullName(), a.status(), a.justification()))
                .toList();
    }

    @Transactional
    void setAttendance(UUID actorId, Set<String> roles, UUID sessionId, UUID athleteId, String status,
                       String justification) {
        UUID tenantId = requireTenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, sessionId)
                .orElseThrow(TrainingService::sessionNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        requireWritable(roles);
        if (!athleteOnTeam(tenantId, row.teamId(), athleteId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Atleta não pertence à equipa");
        }
        String st = validAttendanceStatus(status == null ? "PRESENT" : status);
        sessions.upsertAttendance(tenantId, sessionId, athleteId, st, justification, actorId);
        sessions.audit(tenantId, actorId, "ATTENDANCE_SET", sessionId);
    }

    @Transactional
    void removeAttendance(UUID actorId, Set<String> roles, UUID sessionId, UUID athleteId) {
        UUID tenantId = requireTenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, sessionId)
                .orElseThrow(TrainingService::sessionNotFound);
        requireVisibleTeam(tenantId, row.teamId(), actorId, roles);
        requireWritable(roles);
        if (!sessions.deleteAttendance(tenantId, sessionId, athleteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Presença não encontrada");
        }
        sessions.audit(tenantId, actorId, "ATTENDANCE_REMOVED", sessionId);
    }

    // --- helpers ---

    private void requireMember(Set<String> roles) {
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

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
        if (roles.contains("ATLETA") || roles.contains("ENCARREGADO")) {
            if (!teamHasAthleteLinked(tenantId, teamId, actorId)) throw teamNotFound();
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private boolean isVisibleTeam(UUID tenantId, UUID teamId, UUID actorId, Set<String> roles) {
        if (isManager(roles)) {
            return teamExists(tenantId, teamId);
        }
        if (roles.contains("TREINADOR")) {
            return teamVisibleToStaff(tenantId, teamId, actorId);
        }
        if (roles.contains("ATLETA") || roles.contains("ENCARREGADO")) {
            return teamHasAthleteLinked(tenantId, teamId, actorId);
        }
        return false;
    }

    private boolean teamExists(UUID tenantId, UUID teamId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM team WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL)
                        """)
                .param("tenant", tenantId)
                .param("id", teamId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    private boolean teamHasAthleteLinked(UUID tenantId, UUID teamId, UUID actorId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM team_athlete ta JOIN athlete a
                            ON a.tenant_id = ta.tenant_id AND a.id = ta.athlete_id
                          WHERE ta.tenant_id = :t AND ta.team_id = :team AND a.anonymized_at IS NULL
                            AND (a.user_id = :a OR EXISTS (
                                SELECT 1 FROM athlete_guardian g
                                WHERE g.tenant_id = a.tenant_id AND g.athlete_id = a.id AND g.user_id = :a)))
                        """)
                .param("t", tenantId).param("team", teamId).param("a", actorId)
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    private boolean teamVisibleToStaff(UUID tenantId, UUID teamId, UUID actorId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM team t
                            WHERE t.tenant_id = :tenant AND t.id = :id AND t.anonymized_at IS NULL
                              AND EXISTS (SELECT 1 FROM team_staff s
                                          WHERE s.tenant_id = t.tenant_id AND s.team_id = t.id
                                            AND s.user_id = :actor))
                        """)
                .param("tenant", tenantId)
                .param("id", teamId)
                .param("actor", actorId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    private boolean athleteOnTeam(UUID tenantId, UUID teamId, UUID athleteId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM team_athlete
                            WHERE tenant_id = :tenant AND team_id = :team AND athlete_id = :athlete)
                        """)
                .param("tenant", tenantId)
                .param("team", teamId)
                .param("athlete", athleteId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    private static void requireManager(Set<String> roles) {
        if (!isManager(roles)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static boolean isManager(Set<String> roles) {
        return roles.stream().anyMatch(MANAGERS::contains);
    }

    private static String validStatus(String status) {
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado inválido");
        }
        return normalized;
    }

    private static String validAttendanceStatus(String status) {
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ATTENDANCE_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de presença inválido");
        }
        return normalized;
    }

    private static String requiredText(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome obrigatório");
        }
        return value.trim();
    }

    private static UUID requireTenant() {
        return TenantContext.current().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube: usa o subdomínio do clube"));
    }

    private static ResponseStatusException venueNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Local não encontrado");
    }

    private static ResponseStatusException sessionNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessão de treino não encontrada");
    }

    private static ResponseStatusException teamNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipa não encontrada");
    }

    private static VenueView venueView(VenueRepository.VenueRow row) {
        return new VenueView(row.id(), row.name(), row.address(), row.createdAt(), row.updatedAt());
    }

    private static SessionView sessionView(TrainingSessionRepository.SessionRow row) {
        return new SessionView(row.id(), row.teamId(), row.courtId(), row.startTime(), row.endTime(),
                row.recurrence(), row.notes(), row.status(), row.createdAt(), row.updatedAt());
    }
}