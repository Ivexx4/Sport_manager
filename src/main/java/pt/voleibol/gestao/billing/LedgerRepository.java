package pt.voleibol.gestao.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import pt.voleibol.gestao.shared.Db;

@Repository
class LedgerRepository {
    record Row(UUID id, String type, String category, long amountCents, LocalDate entryDate, String description,
               Instant createdAt) {
    }

    private final JdbcClient jdbc;
    LedgerRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<Row> list(UUID tenantId) {
        return jdbc.sql("""
                SELECT id, type, category, amount_cents, entry_date, description, created_at
                FROM ledger_entry WHERE tenant_id = :t AND anonymized_at IS NULL ORDER BY entry_date DESC, id
                """).param("t", tenantId).query(LedgerRepository::map).list();
    }

    void insert(UUID id, UUID tenantId, String type, String category, long amountCents, LocalDate entryDate, String description, UUID actorId) {
        jdbc.sql("""
                INSERT INTO ledger_entry (id, tenant_id, type, category, amount_cents, entry_date, description, created_by)
                VALUES (:id,:t,:type,:cat,:amount,:date,:desc,:actor)
                """).param("id", id).param("t", tenantId).param("type", type).param("cat", category)
                .param("amount", amountCents).param("date", entryDate).param("desc", description).param("actor", actorId).update();
    }

    boolean anonymize(UUID tenantId, UUID id) {
        return jdbc.sql("UPDATE ledger_entry SET anonymized_at=now(), updated_at=now() WHERE tenant_id=:t AND id=:id AND anonymized_at IS NULL")
                .param("t", tenantId).param("id", id).update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID entityId) {
        Db.audit(jdbc, tenantId, actorId, action, "ledger_entry", entityId);
    }

    private static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(Db.uuid(rs, "id"), rs.getString("type"), rs.getString("category"),
                rs.getLong("amount_cents"), Db.date(rs, "entry_date"),
                rs.getString("description"), Db.instant(rs, "created_at"));
    }
}