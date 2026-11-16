package pt.voleibol.gestao.training;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Rules;
import pt.voleibol.gestao.teams.TeamAccess;

@Service
class TrainingService {

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
    List<VenueView> listVenues(CurrentUser user) {
        Rules.requireMember(user);
        return venues.list(Rules.tenant()).stream()
                .map(v -> new VenueView(v.id(), v.name(), v.address(), v.createdAt(), v.updatedAt())).toList();
    }

    @Transactional(readOnly = true)
    VenueView getVenue(CurrentUser user, UUID id) {
        Rules.requireMember(user);
        return venueView(venues.findById(Rules.tenant(), id).orElseThrow(TrainingService::venueNotFound));
    }

    @Transactional
    VenueView createVenue(CurrentUser user, String name, String address) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        String normalized = Rules.requiredText(name);
        if (venues.nameExists(tenantId, normalized, null)) throw Rules.conflict("Já existe um local com este nome");
        UUID id = UUID.randomUUID();
        venues.insert(id, tenantId, normalized, address, user.id());
        venues.audit(tenantId, user.id(), "VENUE_CREATED", id);
        return venueView(venues.findById(tenantId, id).orElseThrow(TrainingService::venueNotFound));
    }

    @Transactional
    VenueView updateVenue(CurrentUser user, UUID id, String name, String address) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        venues.findById(tenantId, id).orElseThrow(TrainingService::venueNotFound);
        String normalized = Rules.requiredText(name);
        if (venues.nameExists(tenantId, normalized, id)) throw Rules.conflict("Já existe um local com este nome");
        if (!venues.update(tenantId, id, normalized, address)) throw venueNotFound();
        venues.audit(tenantId, user.id(), "VENUE_UPDATED", id);
        return venueView(venues.findById(tenantId, id).orElseThrow(TrainingService::venueNotFound));
    }

    @Transactional
    void deleteVenue(CurrentUser user, UUID id) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        if (!venues.anonymize(tenantId, id)) throw venueNotFound();
        venues.audit(tenantId, user.id(), "VENUE_ANONYMIZED", id);
    }

    // --- sessions ---

    @Transactional(readOnly = true)
    List<SessionView> listSessions(CurrentUser user, UUID teamId) {
        UUID tenantId = Rules.tenant();
        if (!user.isSport()) throw Rules.forbidden();
        if (teamId != null) teamAccess.requireVisible(tenantId, teamId, user);
        return sessions.list(tenantId, teamId).stream()
                .filter(s -> teamAccess.visible(tenantId, s.teamId(), user))
                .map(TrainingService::sessionView).toList();
    }

    @Transactional(readOnly = true)
    SessionView getSession(CurrentUser user, UUID id) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, id)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        return sessionView(row);
    }

    @Transactional
    SessionView createSession(CurrentUser user, UUID teamId, UUID courtId,
                             Instant startTime, Instant endTime, String recurrence, String notes, String status) {
        UUID tenantId = Rules.tenant();
        teamAccess.requireVisible(tenantId, teamId, user);
        Rules.requireWritable(user);
        if (courtId != null && venues.findById(tenantId, courtId).isEmpty()) throw Rules.notFound("Local não encontrado");
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) throw Rules.badRequest("Intervalo de tempo inválido");
        UUID id = UUID.randomUUID();
        sessions.insert(id, tenantId, teamId, courtId, startTime, endTime, recurrence, notes,
                Rules.parse(TrainingStatus.class, status, "Estado", TrainingStatus.DEFAULT).name(), user.id());
        sessions.audit(tenantId, user.id(), "TRAINING_SESSION_CREATED", id);
        return sessionView(sessions.findById(tenantId, id).orElseThrow(TrainingService::sessionNotFound));
    }

    @Transactional
    SessionView updateSession(CurrentUser user, UUID id, UUID teamId, UUID courtId,
                             Instant startTime, Instant endTime, String recurrence, String notes, String status) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow current = sessions.findById(tenantId, id)
                .orElseThrow(TrainingService::sessionNotFound);
        UUID newTeam = teamId == null ? current.teamId() : teamId;
        teamAccess.requireVisible(tenantId, current.teamId(), user);
        teamAccess.requireVisible(tenantId, newTeam, user);
        Rules.requireWritable(user);
        if (courtId != null && venues.findById(tenantId, courtId).isEmpty()) throw Rules.notFound("Local não encontrado");
        Instant s = startTime == null ? current.startTime() : startTime;
        Instant e = endTime == null ? current.endTime() : endTime;
        if (!e.isAfter(s)) throw Rules.badRequest("Intervalo de tempo inválido");
        if (!sessions.update(tenantId, id, newTeam, courtId, s, e, recurrence, notes,
                status == null || status.isBlank() ? current.status()
                        : Rules.parse(TrainingStatus.class, status, "Estado").name())) {
            throw sessionNotFound();
        }
        sessions.audit(tenantId, user.id(), "TRAINING_SESSION_UPDATED", id);
        return sessionView(sessions.findById(tenantId, id).orElseThrow(TrainingService::sessionNotFound));
    }

    @Transactional
    void deleteSession(CurrentUser user, UUID id) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, id)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        Rules.requireWritable(user);
        if (!sessions.anonymize(tenantId, id)) throw sessionNotFound();
        sessions.audit(tenantId, user.id(), "TRAINING_SESSION_ANONYMIZED", id);
    }

    // --- attendance ---

    @Transactional(readOnly = true)
    List<AttendanceView> listAttendance(CurrentUser user, UUID sessionId) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, sessionId)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        return sessions.listAttendance(tenantId, sessionId).stream()
                .map(a -> new AttendanceView(a.athleteId(), a.fullName(), a.status(), a.justification()))
                .toList();
    }

    @Transactional
    void setAttendance(CurrentUser user, UUID sessionId, UUID athleteId, String status,
                       String justification) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, sessionId)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        Rules.requireWritable(user);
        if (!teamAccess.athleteOnTeam(tenantId, row.teamId(), athleteId)) throw Rules.badRequest("Atleta não pertence à equipa");
        String st = Rules.parse(AttendanceStatus.class, status, "Estado de presença", AttendanceStatus.DEFAULT).name();
        sessions.upsertAttendance(tenantId, sessionId, athleteId, st, justification, user.id());
        sessions.audit(tenantId, user.id(), "ATTENDANCE_SET", sessionId);
    }

    @Transactional
    void removeAttendance(CurrentUser user, UUID sessionId, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        TrainingSessionRepository.SessionRow row = sessions.findById(tenantId, sessionId)
                .orElseThrow(TrainingService::sessionNotFound);
        teamAccess.requireVisible(tenantId, row.teamId(), user);
        Rules.requireWritable(user);
        if (!sessions.deleteAttendance(tenantId, sessionId, athleteId)) throw Rules.notFound("Presença não encontrada");
        sessions.audit(tenantId, user.id(), "ATTENDANCE_REMOVED", sessionId);
    }

    // --- helpers ---

    private static ResponseStatusException venueNotFound() {
        return Rules.notFound("Local não encontrado");
    }

    private static ResponseStatusException sessionNotFound() {
        return Rules.notFound("Sessão de treino não encontrada");
    }

    private static VenueView venueView(VenueRepository.VenueRow row) {
        return new VenueView(row.id(), row.name(), row.address(), row.createdAt(), row.updatedAt());
    }

    private static SessionView sessionView(TrainingSessionRepository.SessionRow row) {
        return new SessionView(row.id(), row.teamId(), row.courtId(), row.startTime(), row.endTime(),
                row.recurrence(), row.notes(), row.status(), row.createdAt(), row.updatedAt());
    }
}