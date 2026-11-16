package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import pt.voleibol.gestao.shared.Db;

/**
 * Autorizações excepcionais de subida de escalão. A autorização vale para uma época, um atleta e
 * um escalão concreto; só o estado {@code ACTIVE} desbloqueia a regra federativa.
 */
@Repository
class PromotionRepository {

    record PromotionRow(UUID id, UUID athleteId, String athleteName, UUID ageGroupId, String ageGroupCode,
                        String ageGroupName, String status, String reason, UUID authorizedBy,
                        Instant createdAt, Instant updatedAt) {
    }

    private static final String SELECT = """
            SELECT p.id, p.athlete_id, a.full_name, p.age_group_id, g.code AS age_group_code,
                   g.name AS age_group_name, p.status, p.reason, p.authorized_by, p.created_at, p.updated_at
            FROM season_promotion p
            JOIN athlete a ON a.tenant_id = p.tenant_id AND a.id = p.athlete_id
            JOIN age_group g ON g.id = p.age_group_id
            """;

    private final JdbcClient jdbc;

    PromotionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Autorização ativa para a época e o escalão (só {@code ACTIVE}, nunca anonimizada). */
    boolean activeExists(UUID tenantId, UUID seasonId, UUID ageGroupId, UUID athleteId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM season_promotion sp
                            WHERE sp.tenant_id = :tenant AND sp.season_id = :season
                              AND sp.athlete_id = :athlete AND sp.age_group_id = :age_group
                              AND sp.status = 'ACTIVE' AND sp.anonymized_at IS NULL)
                        """)
                .param("tenant", tenantId).param("season", seasonId).param("athlete", athleteId)
                .param("age_group", ageGroupId));
    }

    /** Escalões autorizados ao atleta na época, usados pelas revalidações. */
    List<UUID> activeAgeGroups(UUID tenantId, UUID seasonId, UUID athleteId) {
        return jdbc.sql("""
                        SELECT sp.age_group_id FROM season_promotion sp
                        WHERE sp.tenant_id = :tenant AND sp.season_id = :season
                          AND sp.athlete_id = :athlete AND sp.status = 'ACTIVE'
                          AND sp.anonymized_at IS NULL
                        """)
                .param("tenant", tenantId).param("season", seasonId).param("athlete", athleteId)
                .query(UUID.class).list();
    }

    Optional<PromotionRow> find(UUID tenantId, UUID seasonId, UUID promotionId) {
        return jdbc.sql(SELECT + """
                         WHERE p.tenant_id = :tenant AND p.season_id = :season AND p.id = :id
                           AND p.anonymized_at IS NULL
                        """)
                .param("tenant", tenantId).param("season", seasonId).param("id", promotionId)
                .query(PromotionRepository::map).optional();
    }

    Optional<PromotionRow> find(UUID tenantId, UUID seasonId, UUID ageGroupId, UUID athleteId) {
        return jdbc.sql(SELECT + """
                         WHERE p.tenant_id = :tenant AND p.season_id = :season
                           AND p.age_group_id = :age_group AND p.athlete_id = :athlete
                           AND p.anonymized_at IS NULL
                        """)
                .param("tenant", tenantId).param("season", seasonId).param("age_group", ageGroupId)
                .param("athlete", athleteId)
                .query(PromotionRepository::map).optional();
    }

    List<PromotionRow> list(UUID tenantId, UUID seasonId, UUID athleteId) {
        String sql = SELECT + " WHERE p.tenant_id = :tenant AND p.season_id = :season"
                + " AND p.anonymized_at IS NULL"
                + (athleteId == null ? "" : " AND p.athlete_id = :athlete")
                + " ORDER BY a.full_name, g.min_age, p.id";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenant", tenantId).param("season", seasonId);
        if (athleteId != null) spec = spec.param("athlete", athleteId);
        return spec.query(PromotionRepository::map).list();
    }

    void insert(UUID id, UUID tenantId, UUID seasonId, UUID athleteId, UUID ageGroupId, String reason,
                UUID actorId) {
        jdbc.sql("""
                        INSERT INTO season_promotion
                            (id, tenant_id, season_id, athlete_id, age_group_id, reason, authorized_by,
                             status, created_by)
                        VALUES (:id, :tenant, :season, :athlete, :age_group, :reason, :actor, 'ACTIVE', :actor)
                        """)
                .param("id", id).param("tenant", tenantId).param("season", seasonId)
                .param("athlete", athleteId).param("age_group", ageGroupId).param("reason", reason)
                .param("actor", actorId)
                .update();
    }

    boolean setStatus(UUID tenantId, UUID seasonId, UUID promotionId, String status) {
        return jdbc.sql("""
                        UPDATE season_promotion
                        SET status = :status, updated_at = now()
                        WHERE tenant_id = :tenant AND season_id = :season AND id = :id
                          AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId).param("season", seasonId).param("id", promotionId)
                .param("status", status)
                .update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID promotionId) {
        Db.audit(jdbc, tenantId, actorId, action, "season_promotion", promotionId);
    }

    private static PromotionRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PromotionRow(Db.uuid(rs, "id"), Db.uuid(rs, "athlete_id"), rs.getString("full_name"),
                Db.uuid(rs, "age_group_id"), rs.getString("age_group_code"), rs.getString("age_group_name"),
                rs.getString("status"), rs.getString("reason"), Db.uuid(rs, "authorized_by"),
                Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));
    }
}
