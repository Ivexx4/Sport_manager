package pt.voleibol.gestao.billing;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {
    record Row(UUID id, UUID chargeId, String method, long amountCents, String reference, Instant paidAt) {
    }

    private final JdbcClient jdbc;
    PaymentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<Row> list(UUID tenantId, UUID chargeId) {
        return jdbc.sql("""
                SELECT id, charge_id, method, amount_cents, reference, paid_at
                FROM payment WHERE tenant_id = :t AND charge_id = :c AND anonymized_at IS NULL ORDER BY paid_at, id
                """).param("t", tenantId).param("c", chargeId).query(PaymentRepository::map).list();
    }

    void insert(UUID id, UUID tenantId, UUID chargeId, String method, long amountCents, String reference, UUID actorId) {
        jdbc.sql("""
                INSERT INTO payment (id, tenant_id, charge_id, method, amount_cents, reference, created_by)
                VALUES (:id,:t,:c,:m,:amount,:ref,:actor)
                """).param("id", id).param("t", tenantId).param("c", chargeId).param("m", method)
                .param("amount", amountCents).param("ref", reference).param("actor", actorId).update();
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID entityId) {
        jdbc.sql("INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id) VALUES (:t,:a,:act,'payment',:e)")
                .param("t", tenantId).param("a", actorId).param("act", action).param("e", entityId).update();
    }

    private static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("charge_id", UUID.class),
                rs.getString("method"), rs.getLong("amount_cents"), rs.getString("reference"),
                rs.getObject("paid_at", OffsetDateTime.class).toInstant());
    }
}