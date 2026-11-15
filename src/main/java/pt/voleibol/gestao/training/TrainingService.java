package pt.voleibol.gestao.training;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.Rules;
import pt.voleibol.gestao.teams.TeamAccess;

@Service
class TrainingService {

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
    private final TeamAccess teamAccess;

    TrainingService(VenueRepository venues, TrainingSessionRepository sessions, TeamAccess teamAccess) {
        this.venues = venues;
        this.sessions = sessions;
        this.teamAccess = teamAccess;
    }

    // --- venues ---

    @Transactional(readOnly = true)
    List<VenueView> listVenues(UUID actorId, Set<String> roles) {
        Rules.requireMember(roles);
        return venues.list(Rules.tenant()).stream()
                .map(v -> new VenueView(v.id(), v.name(), v.address(), v.createdAt(), v.updatedAt())).toList();
    }

    @Transactional(readOnly = true)
    VenueView getVenue(UUID actorId, Set<String> roles, UUID id) {
        Rules.requireMember(roles);
        return venueView(venues.findById(Rules.tenant(), id).orElseThrow(TrainingService::venueNotFound));
    }

    @Transactional
    VenueView createVenue(UUID actorId, Set<String> roles, String name, String address) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        String normalized = Rules.requiredText(name);
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
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        venues.findById(tenantId, id).orElseThrow(TrainingService::venueNotFound);
        String normalized = Rules.requiredText(name);
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
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        if (!venues.anonymize(tenantId, id)) {
            throw venueNotFound();
        }
        venues.audit(tenantId, actorId, "VENUE_ANONYMIZED", id);
    }

    // --- sessions ---

    @Transactional(readOnly = true)
    List<SessionView> listSessions(UUID actorId, Set<String> roles, UUID teamId) {
        UUID tenantId = Rules.tenant();
        boolean writer = Rules.isManager(roles) || roles.contains("TREINADOR");
        boolean athleteOrGuardian = roles.contains("ATLETA") || roles.contains("ENCARREGADO");
        if (!writer && !athleteOrGuardian) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (teamId != null) {
            teamAccess.requireVisible(tenantId, teamId, actorId, roles);
        }
        return sessions.list(tenantId, teamId).stream()
                .filter(s -> teamAccess.visible(tenantId, s.teamId(), actorId, roles))
                .map(TrainingService::sessionView).toList();
    }

    @Transactional(readOnly = true)
    SessionView getSession(UUID actorId, Set<String> roles, UUID id) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, id)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), actorId, roles);
        return sessionView(row);
    }

    @Transactional
    SessionView createSession(UUID actorId, Set<String> roles, UUID teamId, UUID courtId,
                             Instant startTime, Instant endTime, String recurrence, String notes, String status) {
        UUID tenantId = Rules.tenant();
        teamAccess.requireVisible(tenantId, teamId, actorId, roles);
        Rules.requireWritable(roles);
        if (courtId != null && venues.findById(tenantId, courtId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local não encontrado");
        }
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Intervalo de tempo inválido");
        }
        UUID id = UUID.randomUUID();
        sessions.insert(id, tenantId, teamId, courtId, startTime, endTime, recurrence, notes,
                Rules.status(status == null ? "ACTIVE" : status, STATUSES), actorId);
        sessions.audit(tenantId, actorId, "TRAINING_SESSION_CREATED", id);
        return sessionView(sessions.findById(tenantId, id).orElseThrow(TrainingService::sessionNotFound));
    }

    @Transactional
    SessionView updateSession(UUID actorId, Set<String> roles, UUID id, UUID teamId, UUID courtId,
                             Instant startTime, Instant endTime, String recurrence, String notes, String status) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow current = sessions.findById(tenantId, id)
                .orElseThrow(TrainingService::sessionNotFound);
        UUID newTeam = teamId == null ? current.teamId() : teamId;
        teamAccess.requireVisible(tenantId, current.teamId(), actorId, roles);
        teamAccess.requireVisible(tenantId, newTeam, actorId, roles);
        Rules.requireWritable(roles);
        if (courtId != null && venues.findById(tenantId, courtId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local não encontrado");
        }
        Instant s = startTime == null ? current.startTime() : startTime;
        Instant e = endTime == null ? current.endTime() : endTime;
        if (!e.isAfter(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Intervalo de tempo inválido");
        }
        if (!sessions.update(tenantId, id, newTeam, courtId, s, e, recurrence, notes,
                status == null || status.isBlank() ? current.status() : Rules.status(status, STATUSES))) {
            throw sessionNotFound();
        }
        sessions.audit(tenantId, actorId, "TRAINING_SESSION_UPDATED", id);
        return sessionView(sessions.findById(tenantId, id).orElseThrow(TrainingService::sessionNotFound));
    }

    @Transactional
    void deleteSession(UUID actorId, Set<String> roles, UUID id) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, id)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), actorId, roles);
        Rules.requireWritable(roles);
        if (!sessions.anonymize(tenantId, id)) {
            throw sessionNotFound();
        }
        sessions.audit(tenantId, actorId, "TRAINING_SESSION_ANONYMIZED", id);
    }

    // --- attendance ---

    @Transactional(readOnly = true)
    List<AttendanceView> listAttendance(UUID actorId, Set<String> roles, UUID sessionId) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, sessionId)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), actorId, roles);
        return sessions.listAttendance(tenantId, sessionId).stream()
                .map(a -> new AttendanceView(a.athleteId(), a.fullName(), a.status(), a.justification()))
                .toList();
    }

    @Transactional
    void setAttendance(UUID actorId, Set<String> roles, UUID sessionId, UUID athleteId, String status,
                       String justification) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, sessionId)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), actorId, roles);
        Rules.requireWritable(roles);
        if (!teamAccess.athleteOnTeam(tenantId, row.teamId(), athleteId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Atleta não pertence à equipa");
        }
        String st = validAttendanceStatus(status == null ? "PRESENT" : status);
        sessions.upsertAttendance(tenantId, sessionId, athleteId, st, justification, actorId);
        sessions.audit(tenantId, actorId, "ATTENDANCE_SET", sessionId);
    }

    @Transactional
    void removeAttendance(UUID actorId, Set<String> roles, UUID sessionId, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, sessionId)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), actorId, roles);
        Rules.requireWritable(roles);
        if (!sessions.deleteAttendance(tenantId, sessionId, athleteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Presença não encontrada");
        }
        sessions.audit(tenantId, actorId, "ATTENDANCE_REMOVED", sessionId);
    }

    // --- helpers ---




    private static String validAttendanceStatus(String status) {
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ATTENDANCE_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de presença inválido");
        }
        return normalized;
    }



    private static ResponseStatusException venueNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Local não encontrado");
    }

    private static ResponseStatusException sessionNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessão de treino não encontrada");
    }


    private static VenueView venueView(VenueRepository.VenueRow row) {
        return new VenueView(row.id(), row.name(), row.address(), row.createdAt(), row.updatedAt());
    }

    private static SessionView sessionView(TrainingSessionRepository.SessionRow row) {
        return new SessionView(row.id(), row.teamId(), row.courtId(), row.startTime(), row.endTime(),
                row.recurrence(), row.notes(), row.status(), row.createdAt(), row.updatedAt());
    }
}