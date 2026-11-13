package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acesso a dados das épocas. Todas as operações são filtradas pelo tenant recebido e as leituras
 * ignoram registos anonimizados (remoção não destrutiva).
 */
@Repository
class SeasonRepository {

    record SeasonRow(UUID id, String name, LocalDate startDate, LocalDate endDate, boolean isActive,
                     Instant createdAt, Instant updatedAt) {
    }

    record Data(String name, LocalDate startDate, LocalDate endDate, boolean isActive) {
    }

    private static final String SELECT = """
            SELECT s.id, s.name, s.start_date, s.end_date, s.is_active, s.created_at, s.updated_at
            FROM season s
            """;

    private final JdbcClient jdbc;

    SeasonRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<SeasonRow> list(UUID tenantId) {
        return jdbc.sql(SELECT + """
                         WHERE s.tenant_id = :tenant AND s.anonymized_at IS NULL
                         ORDER BY s.start_date DESC NULLS LAST, s.name, s.id
                        """)
                .param("tenant", tenantId)
                .query(SeasonRepository::map)
                .list();
    }

    Optional<SeasonRow> findById(UUID tenantId, UUID seasonId) {
        return jdbc.sql(SELECT + " WHERE s.tenant_id = :tenant AND s.id = :id AND s.anonymized_at IS NULL")
                .param("tenant", tenantId)
                .param("id", seasonId)
                .query(SeasonRepository::map)
                .optional();
    }

    boolean nameExists(UUID tenantId, String name, UUID excludeId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM season WHERE tenant_id = :tenant "
                + "AND anonymized_at IS NULL AND lower(name) = lower(:name)";
        JdbcClient.StatementSpec spec = jdbc.sql(excludeId == null ? sql + ")"
                        : sql + " AND id <> :exclude)")
                .param("tenant", tenantId)
                .param("name", name);
        if (excludeId != null) {
            spec = spec.param("exclude", excludeId);
        }
        return spec.query(Boolean.class).single();
    }

    void insert(UUID id, UUID tenantId, Data data, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO season (id, tenant_id, name, start_date, end_date, is_active, created_by)
                        VALUES (:id, :tenant, :name, :start, :end, :active, :actor)
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("name", data.name())
                .param("start", data.startDate())
                .param("end", data.endDate())
                .param("active", data.isActive())
                .param("actor", actorId)
                .update();
    }

    boolean update(UUID tenantId, UUID seasonId, Data data) {
        return jdbc.sql("""
                        UPDATE season
                        SET name = :name, start_date = :start, end_date = :end, is_active = :active,
                            updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", seasonId)
                .param("name", data.name())
                .param("start", data.startDate())
                .param("end", data.endDate())
                .param("active", data.isActive())
                .update() == 1;
    }

    /**
     * Remoção não destrutiva: desativa a época (marca-a como não ativa) e anonimiza, preservando a
     * linha e as equipas associadas para histórico.
     */
    boolean anonymize(UUID tenantId, UUID seasonId) {
        return jdbc.sql("""
                        UPDATE season
                        SET is_active = false, anonymized_at = now(), updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", seasonId)
                .update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID seasonId) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)
                        VALUES (:tenant, :actor, :action, 'season', :entity)
                        """)
                .param("tenant", tenantId)
                .param("actor", actorId)
                .param("action", action)
                .param("entity", seasonId)
                .update();
    }

    private static SeasonRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SeasonRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getBoolean("is_active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
