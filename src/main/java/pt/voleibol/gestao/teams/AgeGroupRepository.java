package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acesso a dados dos escalões (faixas de anos de nascimento da época). Todas as operações são
 * filtradas pelo tenant recebido; a remoção é não destrutiva e limita-se a anonimizar o registo.
 */
@Repository
class AgeGroupRepository {

    record AgeGroupRow(UUID id, UUID seasonId, String name, int minBirthYear, int maxBirthYear,
                       Instant createdAt, Instant updatedAt) {
    }

    record Data(UUID seasonId, String name, int minBirthYear, int maxBirthYear) {
    }

    private static final String SELECT = """
            SELECT g.id, g.season_id, g.name, g.min_birth_year, g.max_birth_year, g.created_at, g.updated_at
            FROM age_group g
            """;

    private final JdbcClient jdbc;

    AgeGroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<AgeGroupRow> list(UUID tenantId) {
        return jdbc.sql(SELECT + """
                         WHERE g.tenant_id = :tenant AND g.anonymized_at IS NULL
                         ORDER BY g.season_id, g.min_birth_year, g.max_birth_year, g.name, g.id
                        """)
                .param("tenant", tenantId)
                .query(AgeGroupRepository::map)
                .list();
    }

    Optional<AgeGroupRow> findById(UUID tenantId, UUID ageGroupId) {
        return jdbc.sql(SELECT + " WHERE g.tenant_id = :tenant AND g.id = :id AND g.anonymized_at IS NULL")
                .param("tenant", tenantId)
                .param("id", ageGroupId)
                .query(AgeGroupRepository::map)
                .optional();
    }

    boolean nameExists(UUID tenantId, UUID seasonId, String name, UUID excludeId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM age_group WHERE tenant_id = :tenant "
                + "AND season_id = :season AND anonymized_at IS NULL AND lower(name) = lower(:name)";
        JdbcClient.StatementSpec spec = jdbc.sql(excludeId == null ? sql + ")"
                        : sql + " AND id <> :exclude)")
                .param("tenant", tenantId)
                .param("season", seasonId)
                .param("name", name);
        if (excludeId != null) {
            spec = spec.param("exclude", excludeId);
        }
        return spec.query(Boolean.class).single();
    }

    void insert(UUID id, UUID tenantId, Data data, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO age_group (id, tenant_id, season_id, name, min_birth_year, max_birth_year,
                                               created_by)
                        VALUES (:id, :tenant, :season, :name, :min, :max, :actor)
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("season", data.seasonId())
                .param("name", data.name())
                .param("min", data.minBirthYear())
                .param("max", data.maxBirthYear())
                .param("actor", actorId)
                .update();
    }

    boolean update(UUID tenantId, UUID ageGroupId, Data data) {
        return jdbc.sql("""
                        UPDATE age_group
                        SET season_id = :season, name = :name, min_birth_year = :min, max_birth_year = :max,
                            updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", ageGroupId)
                .param("season", data.seasonId())
                .param("name", data.name())
                .param("min", data.minBirthYear())
                .param("max", data.maxBirthYear())
                .update() == 1;
    }

    /**
     * Remoção não destrutiva: anonimiza o escalão, preservando o registo, o nome e as equipas
     * associadas para histórico.
     */
    boolean anonymize(UUID tenantId, UUID ageGroupId) {
        return jdbc.sql("""
                        UPDATE age_group
                        SET anonymized_at = now(), updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", ageGroupId)
                .update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID ageGroupId) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)
                        VALUES (:tenant, :actor, :action, 'age_group', :entity)
                        """)
                .param("tenant", tenantId)
                .param("actor", actorId)
                .param("action", action)
                .param("entity", ageGroupId)
                .update();
    }

    private static AgeGroupRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AgeGroupRow(
                rs.getObject("id", UUID.class),
                rs.getObject("season_id", UUID.class),
                rs.getString("name"),
                rs.getInt("min_birth_year"),
                rs.getInt("max_birth_year"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
