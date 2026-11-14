package pt.voleibol.gestao.training;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class VenueRepository {

    record VenueRow(UUID id, String name, String address, Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    VenueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<VenueRow> list(UUID tenantId) {
        return jdbc.sql("""
                        SELECT id, name, address, created_at, updated_at
                        FROM venue
                        WHERE tenant_id = :tenant AND anonymized_at IS NULL
                        ORDER BY name, id
                        """)
                .param("tenant", tenantId)
                .query(VenueRepository::map)
                .list();
    }

    Optional<VenueRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        SELECT id, name, address, created_at, updated_at
                        FROM venue
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .query(VenueRepository::map)
                .optional();
    }

    boolean nameExists(UUID tenantId, String name, UUID excludeId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM venue WHERE tenant_id = :tenant AND anonymized_at IS NULL "
                + "AND lower(name) = lower(:name)";
        var spec = jdbc.sql(excludeId == null ? sql + ")" : sql + " AND id <> :exclude)")
                .param("tenant", tenantId)
                .param("name", name);
        if (excludeId != null) {
            spec = spec.param("exclude", excludeId);
        }
        return spec.query(Boolean.class).single();
    }

    void insert(UUID id, UUID tenantId, String name, String address, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO venue (id, tenant_id, name, address, created_by)
                        VALUES (:id, :tenant, :name, :address, :actor)
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("name", name)
                .param("address", address)
                .param("actor", actorId)
                .update();
    }

    boolean update(UUID tenantId, UUID id, String name, String address) {
        return jdbc.sql("""
                        UPDATE venue
                        SET name = :name, address = :address, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .param("name", name)
                .param("address", address)
                .update() == 1;
    }

    boolean anonymize(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        UPDATE venue
                        SET anonymized_at = now(), name = 'Local anonimizado', updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID entityId) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)
                        VALUES (:tenant, :actor, :action, 'venue', :entity)
                        """)
                .param("tenant", tenantId)
                .param("actor", actorId)
                .param("action", action)
                .param("entity", entityId)
                .update();
    }

    private static VenueRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new VenueRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("address"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}