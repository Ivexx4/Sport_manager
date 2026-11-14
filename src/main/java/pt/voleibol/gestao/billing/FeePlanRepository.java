package pt.voleibol.gestao.billing;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FeePlanRepository {
    record Row(UUID id, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity,
               Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    FeePlanRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<Row> list(UUID tenantId) {
        return jdbc.sql("""
                SELECT id, name, season_id, age_group_id, amount_cents, periodicity, created_at, updated_at
                FROM fee_plan WHERE tenant_id = :t AND anonymized_at IS NULL ORDER BY name, id
                """).param("t", tenantId).query(FeePlanRepository::map).list();
    }

    Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql("""
                SELECT id, name, season_id, age_group_id, amount_cents, periodicity, created_at, updated_at
                FROM fee_plan WHERE tenant_id = :t AND id = :id AND anonymized_at IS NULL
                """).param("t", tenantId).param("id", id).query(FeePlanRepository::map).optional();
    }

    boolean nameExists(UUID tenantId, String name, UUID excludeId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM fee_plan WHERE tenant_id = :t AND anonymized_at IS NULL AND lower(name)=lower(:n)";
        var spec = jdbc.sql(excludeId == null ? sql + ")" : sql + " AND id <> :e)").param("t", tenantId).param("n", name);
        if (excludeId != null) spec = spec.param("e", excludeId);
        return spec.query(Boolean.class).single();
    }

    void insert(UUID id, UUID tenantId, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity, UUID actorId) {
        jdbc.sql("""
                INSERT INTO fee_plan (id, tenant_id, name, season_id, age_group_id, amount_cents, periodicity, created_by)
                VALUES (:id,:t,:name,:season,:age,:amount,:period,:actor)
                """).param("id", id).param("t", tenantId).param("name", name).param("season", seasonId)
                .param("age", ageGroupId).param("amount", amountCents).param("period", periodicity).param("actor", actorId).update();
    }

    boolean update(UUID tenantId, UUID id, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity) {
        return jdbc.sql("""
                UPDATE fee_plan SET name=:name, season_id=:season, age_group_id=:age, amount_cents=:amount, periodicity=:period, updated_at=now()
                WHERE tenant_id=:t AND id=:id AND anonymized_at IS NULL
                """).param("t", tenantId).param("id", id).param("name", name).param("season", seasonId)
                .param("age", ageGroupId).param("amount", amountCents).param("period", periodicity).update() == 1;
    }

    boolean anonymize(UUID tenantId, UUID id) {
        return jdbc.sql("UPDATE fee_plan SET anonymized_at=now(), updated_at=now() WHERE tenant_id=:t AND id=:id AND anonymized_at IS NULL")
                .param("t", tenantId).param("id", id).update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID entityId) {
        jdbc.sql("INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id) VALUES (:t,:a,:act,'fee_plan',:e)")
                .param("t", tenantId).param("a", actorId).param("act", action).param("e", entityId).update();
    }

    private static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("season_id", UUID.class), rs.getObject("age_group_id", UUID.class),
                rs.getLong("amount_cents"), rs.getString("periodicity"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}