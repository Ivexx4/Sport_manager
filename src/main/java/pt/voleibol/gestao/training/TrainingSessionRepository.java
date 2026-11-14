package pt.voleibol.gestao.training;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TrainingSessionRepository {

    record SessionRow(UUID id, UUID teamId, UUID courtId, Instant startTime, Instant endTime,
                      String recurrence, String notes, String status, Instant createdAt, Instant updatedAt) {
    }

    record AttendanceRow(UUID athleteId, String fullName, String status, String justification) {
    }

    private final JdbcClient jdbc;

    TrainingSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<SessionRow> list(UUID tenantId, UUID teamId) {
        String sql = """
                SELECT id, team_id, court_id, start_time, end_time, recurrence, notes, status, created_at, updated_at
                FROM training_session
                WHERE tenant_id = :tenant AND anonymized_at IS NULL
                """;
        JdbcClient.StatementSpec spec;
        if (teamId != null) {
            sql = sql + " AND team_id = :team";
            spec = jdbc.sql(sql).param("tenant", tenantId).param("team", teamId);
        } else {
            spec = jdbc.sql(sql).param("tenant", tenantId);
        }
        return spec.query(TrainingSessionRepository::mapSession).list();
    }

    Optional<SessionRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        SELECT id, team_id, court_id, start_time, end_time, recurrence, notes, status, created_at, updated_at
                        FROM training_session
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .query(TrainingSessionRepository::mapSession)
                .optional();
    }

    void insert(UUID id, UUID tenantId, UUID teamId, UUID courtId, Instant startTime, Instant endTime,
                String recurrence, String notes, String status, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO training_session (id, tenant_id, team_id, court_id, start_time, end_time,
                                                      recurrence, notes, status, created_by)
                        VALUES (:id, :tenant, :team, :court, :start, :end, :rec, :notes, :status, :actor)
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("team", teamId)
                .param("court", courtId)
                .param("start", startTime == null ? null : java.time.OffsetDateTime.ofInstant(startTime, java.time.ZoneOffset.UTC))
                .param("end", endTime == null ? null : java.time.OffsetDateTime.ofInstant(endTime, java.time.ZoneOffset.UTC))
                .param("rec", recurrence)
                .param("notes", notes)
                .param("status", status)
                .param("actor", actorId)
                .update();
    }

    boolean update(UUID tenantId, UUID id, UUID teamId, UUID courtId, Instant startTime, Instant endTime,
                   String recurrence, String notes, String status) {
        return jdbc.sql("""
                        UPDATE training_session
                        SET team_id = :team, court_id = :court, start_time = :start, end_time = :end,
                            recurrence = :rec, notes = :notes, status = :status, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .param("team", teamId)
                .param("court", courtId)
                .param("start", startTime == null ? null : java.time.OffsetDateTime.ofInstant(startTime, java.time.ZoneOffset.UTC))
                .param("end", endTime == null ? null : java.time.OffsetDateTime.ofInstant(endTime, java.time.ZoneOffset.UTC))
                .param("rec", recurrence)
                .param("notes", notes)
                .param("status", status)
                .update() == 1;
    }

    boolean anonymize(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        UPDATE training_session
                        SET anonymized_at = now(), status = 'CANCELLED', updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .update() == 1;
    }

    // --- attendance ---

    List<AttendanceRow> listAttendance(UUID tenantId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT a.athlete_id, at.full_name, a.status, a.justification
                        FROM attendance a
                        JOIN athlete at ON at.tenant_id = a.tenant_id AND at.id = a.athlete_id
                        WHERE a.tenant_id = :tenant AND a.session_id = :session AND at.anonymized_at IS NULL
                        ORDER BY at.full_name, at.id
                        """)
                .param("tenant", tenantId)
                .param("session", sessionId)
                .query((rs, i) -> new AttendanceRow(
                        rs.getObject("athlete_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("status"),
                        rs.getString("justification")))
                .list();
    }

    boolean attendanceExists(UUID tenantId, UUID sessionId, UUID athleteId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM attendance
                            WHERE tenant_id = :tenant AND session_id = :session AND athlete_id = :athlete)
                        """)
                .param("tenant", tenantId)
                .param("session", sessionId)
                .param("athlete", athleteId)
                .query(Boolean.class)
                .single();
    }

    void upsertAttendance(UUID tenantId, UUID sessionId, UUID athleteId, String status, String justification,
                          UUID actorId) {
        jdbc.sql("""
                        INSERT INTO attendance (tenant_id, session_id, athlete_id, status, justification, created_by)
                        VALUES (:tenant, :session, :athlete, :status, :just, :actor)
                        ON CONFLICT (tenant_id, session_id, athlete_id)
                        DO UPDATE SET status = EXCLUDED.status, justification = EXCLUDED.justification,
                                      updated_at = now()
                        """)
                .param("tenant", tenantId)
                .param("session", sessionId)
                .param("athlete", athleteId)
                .param("status", status)
                .param("just", justification)
                .param("actor", actorId)
                .update();
    }

    boolean deleteAttendance(UUID tenantId, UUID sessionId, UUID athleteId) {
        return jdbc.sql("""
                        DELETE FROM attendance
                        WHERE tenant_id = :tenant AND session_id = :session AND athlete_id = :athlete
                        """)
                .param("tenant", tenantId)
                .param("session", sessionId)
                .param("athlete", athleteId)
                .update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID entityId) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)
                        VALUES (:tenant, :actor, :action, 'training_session', :entity)
                        """)
                .param("tenant", tenantId)
                .param("actor", actorId)
                .param("action", action)
                .param("entity", entityId)
                .update();
    }

    private static SessionRow mapSession(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("team_id", UUID.class),
                rs.getObject("court_id", UUID.class),
                rs.getObject("start_time", OffsetDateTime.class).toInstant(),
                rs.getObject("end_time", OffsetDateTime.class).toInstant(),
                rs.getString("recurrence"),
                rs.getString("notes"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}