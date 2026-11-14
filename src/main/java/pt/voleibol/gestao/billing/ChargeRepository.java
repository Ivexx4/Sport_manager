package pt.voleibol.gestao.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ChargeRepository {
    record Row(UUID id, UUID athleteId, UUID guardianId, UUID feePlanId, long amountCents, LocalDate dueDate,
               String status, Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    ChargeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<Row> list(UUID tenantId, UUID actorId, Set<String> roles, UUID athleteId, String status) {
        boolean writer = roles.contains("ADMIN") || roles.contains("DIRECAO") || roles.contains("FINANCEIRO");
        StringBuilder sql = new StringBuilder("""
                SELECT id, athlete_id, guardian_id, fee_plan_id, amount_cents, due_date, status, created_at, updated_at
                FROM charge WHERE tenant_id = :t AND anonymized_at IS NULL
                """);
        if (!writer) {
            // ATLETA/ENCARREGADO: só as suas cobranças
            sql.append("""
                     AND (
                       EXISTS (SELECT 1 FROM athlete a WHERE a.tenant_id = charge.tenant_id AND a.id = charge.athlete_id
                               AND a.user_id = :actor AND a.anonymized_at IS NULL)
                       OR EXISTS (SELECT 1 FROM athlete_guardian g WHERE g.tenant_id = charge.tenant_id
                               AND g.athlete_id = charge.athlete_id AND g.user_id = :actor)
                     )
                    """);
        }
        if (athleteId != null) {
            sql.append(" AND athlete_id = :a");
        }
        if (status != null) {
            sql.append(" AND status = :s");
        }
        sql.append(" ORDER BY due_date, id");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("t", tenantId);
        if (!writer) spec = spec.param("actor", actorId);
        if (athleteId != null) spec = spec.param("a", athleteId);
        if (status != null) spec = spec.param("s", status);
        return spec.query(ChargeRepository::map).list();
    }

    Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql("""
                SELECT id, athlete_id, guardian_id, fee_plan_id, amount_cents, due_date, status, created_at, updated_at
                FROM charge WHERE tenant_id = :t AND id = :id AND anonymized_at IS NULL
                """).param("t", tenantId).param("id", id).query(ChargeRepository::map).optional();
    }

    boolean athleteExists(UUID tenantId, UUID athleteId) {
        Boolean exists = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM athlete
                               WHERE tenant_id = :t AND id = :a AND anonymized_at IS NULL)
                """).param("t", tenantId).param("a", athleteId).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    boolean guardianExists(UUID tenantId, UUID userId) {
        Boolean exists = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM membership
                               WHERE tenant_id = :t AND user_id = :u AND status = 'ACTIVE')
                """).param("t", tenantId).param("u", userId).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    void insert(UUID id, UUID tenantId, UUID athleteId, UUID guardianId, UUID feePlanId, long amountCents, LocalDate dueDate, String status, UUID actorId) {
        jdbc.sql("""
                INSERT INTO charge (id, tenant_id, athlete_id, guardian_id, fee_plan_id, amount_cents, due_date, status, created_by)
                VALUES (:id,:t,:a,:g,:f,:amount,:due,:status,:actor)
                """).param("id", id).param("t", tenantId).param("a", athleteId).param("g", guardianId)
                .param("f", feePlanId).param("amount", amountCents).param("due", dueDate).param("status", status).param("actor", actorId).update();
    }

    boolean updateStatus(UUID tenantId, UUID id, String status) {
        return jdbc.sql("UPDATE charge SET status=:s, updated_at=now() WHERE tenant_id=:t AND id=:id AND anonymized_at IS NULL")
                .param("t", tenantId).param("id", id).param("s", status).update() == 1;
    }

    boolean anonymize(UUID tenantId, UUID id) {
        return jdbc.sql("UPDATE charge SET anonymized_at=now(), updated_at=now() WHERE tenant_id=:t AND id=:id AND anonymized_at IS NULL")
                .param("t", tenantId).param("id", id).update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID entityId) {
        jdbc.sql("INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id) VALUES (:t,:a,:act,'charge',:e)")
                .param("t", tenantId).param("a", actorId).param("act", action).param("e", entityId).update();
    }

    private static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("athlete_id", UUID.class),
                rs.getObject("guardian_id", UUID.class), rs.getObject("fee_plan_id", UUID.class),
                rs.getLong("amount_cents"), rs.getObject("due_date", java.sql.Date.class).toLocalDate(),
                rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}