package pt.voleibol.gestao.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import pt.voleibol.gestao.shared.Db;

@Repository
class FeePlanRepository {
    record SeasonWindow(java.time.LocalDate startDate, java.time.LocalDate endDate) {
    }

    record Row(UUID id, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity,
               boolean active, java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo,
               Instant createdAt, Instant updatedAt) {
        Row(UUID id, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity,
            Instant createdAt, Instant updatedAt) {
            this(id, name, seasonId, ageGroupId, amountCents, periodicity, true, null, null, createdAt, updatedAt);
        }

    }

    record Promotion(UUID id, UUID athleteId, UUID feePlanId, long amountCents,
                     java.time.LocalDate validFrom, java.time.LocalDate validTo,
                     String reason, boolean active, String applicationScope, String billingPeriod) {
    }

    private final JdbcClient jdbc;

    FeePlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> list(UUID tenantId) {
        return jdbc.sql("""
                SELECT id, name, season_id, age_group_id, amount_cents, periodicity, is_active,
                       effective_from, effective_to, created_at, updated_at
                FROM fee_plan WHERE tenant_id = :t AND anonymized_at IS NULL ORDER BY name, id
                """).param("t", tenantId).query(FeePlanRepository::map).list();
    }

    Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql("""
                SELECT id, name, season_id, age_group_id, amount_cents, periodicity, is_active,
                       effective_from, effective_to, created_at, updated_at
                FROM fee_plan WHERE tenant_id = :t AND id = :id AND anonymized_at IS NULL
                """).param("t", tenantId).param("id", id).query(FeePlanRepository::map).optional();
    }

    Optional<SeasonWindow> seasonWindow(UUID tenantId, UUID seasonId) {
        return jdbc.sql("""
                        SELECT start_date, end_date
                        FROM season
                        WHERE tenant_id = :t AND id = :id AND anonymized_at IS NULL
                        """).param("t", tenantId).param("id", seasonId)
                .query((rs, n) -> new SeasonWindow(
                        Db.date(rs, "start_date"),
                        Db.date(rs, "end_date")))
                .optional();
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

    Optional<Long> overrideAmount(UUID tenantId, UUID athleteId, UUID feePlanId,
                                  java.time.LocalDate date, String billingPeriod) {
        return jdbc.sql("""
                        SELECT amount_cents
                        FROM athlete_fee_override
                        WHERE tenant_id = :t AND athlete_id = :a
                          AND (fee_plan_id IS NULL OR fee_plan_id = :f)
                          AND (application_scope = 'ALL'
                               OR (application_scope = 'SINGLE' AND billing_period = :period))
                          AND is_active AND valid_from <= :date AND (valid_to IS NULL OR valid_to >= :date)
                        ORDER BY CASE WHEN fee_plan_id = :f THEN 0 ELSE 1 END, valid_from DESC, id DESC
                        LIMIT 1
                        """).param("t", tenantId).param("a", athleteId).param("f", feePlanId).param("date", date)
                .param("period", billingPeriod).query(Long.class).optional();
    }

    List<Promotion> listPromotions(UUID tenantId, UUID athleteId) {
        return jdbc.sql("""
                SELECT id, athlete_id, fee_plan_id, amount_cents, valid_from, valid_to, reason, is_active,
                       application_scope, billing_period
                FROM athlete_fee_override
                WHERE tenant_id = :t AND athlete_id = :a
                ORDER BY is_active DESC, valid_from DESC, id
                """).param("t", tenantId).param("a", athleteId).query((rs, n) -> new Promotion(
                rs.getObject("id", UUID.class), rs.getObject("athlete_id", UUID.class),
                rs.getObject("fee_plan_id", UUID.class), rs.getLong("amount_cents"),
                Db.date(rs, "valid_from"),
                Db.date(rs, "valid_to"),
                rs.getString("reason"), rs.getBoolean("is_active"),
                rs.getString("application_scope"), rs.getString("billing_period"))).list();
    }

    void insertPromotion(UUID id, UUID tenantId, UUID athleteId, UUID feePlanId, long amountCents,
                         java.time.LocalDate validFrom, java.time.LocalDate validTo, String reason,
                         String applicationScope, String billingPeriod, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO athlete_fee_override
                            (id, tenant_id, athlete_id, fee_plan_id, amount_cents, valid_from, valid_to, reason,
                             application_scope, billing_period, created_by)
                        VALUES (:id, :t, :a, :f, :amount, :from, :to, :reason, :scope, :period, :actor)
                        """).param("id", id).param("t", tenantId).param("a", athleteId).param("f", feePlanId)
                .param("amount", amountCents).param("from", validFrom).param("to", validTo)
                .param("reason", reason).param("scope", applicationScope).param("period", billingPeriod)
                .param("actor", actorId).update();
    }

    boolean setPromotionActive(UUID tenantId, UUID id, boolean active) {
        return jdbc.sql("""
                UPDATE athlete_fee_override SET is_active = :active, updated_at = now()
                WHERE tenant_id = :t AND id = :id
                """).param("t", tenantId).param("id", id).param("active", active).update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID entityId) {
        Db.audit(jdbc, tenantId, actorId, action, "fee_plan", entityId);
    }

    private static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("season_id", UUID.class), rs.getObject("age_group_id", UUID.class),
                rs.getLong("amount_cents"), rs.getString("periodicity"), rs.getBoolean("is_active"),
                Db.date(rs, "effective_from"),
                Db.date(rs, "effective_to"),
                Db.instant(rs, "created_at"),
                Db.instant(rs, "updated_at"));
    }
}
