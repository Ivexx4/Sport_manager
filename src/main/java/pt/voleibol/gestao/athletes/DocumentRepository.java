package pt.voleibol.gestao.athletes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class DocumentRepository {

    record DocumentRow(UUID id, UUID athleteId, String type, String fileName, String fileUrl,
                       LocalDate validUntil, java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<DocumentRow> list(UUID tenantId, UUID athleteId) {
        return jdbc.sql("""
                        SELECT id, athlete_id, type, file_name, file_url, valid_until, created_at, updated_at
                        FROM document
                        WHERE tenant_id = :tenant AND athlete_id = :athlete AND anonymized_at IS NULL
                        ORDER BY created_at DESC, id
                        """)
                .param("tenant", tenantId)
                .param("athlete", athleteId)
                .query(DocumentRepository::map)
                .list();
    }

    Optional<DocumentRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        SELECT id, athlete_id, type, file_name, file_url, valid_until, created_at, updated_at
                        FROM document
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .query(DocumentRepository::map)
                .optional();
    }

    void insert(UUID id, UUID tenantId, UUID athleteId, String type, String fileName, String fileUrl,
                LocalDate validUntil, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO document (id, tenant_id, athlete_id, type, file_name, file_url, valid_until, created_by)
                        VALUES (:id, :tenant, :athlete, :type, :name, :url, :valid, :actor)
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("athlete", athleteId)
                .param("type", type)
                .param("name", fileName)
                .param("url", fileUrl)
                .param("valid", validUntil)
                .param("actor", actorId)
                .update();
    }

    boolean update(UUID tenantId, UUID id, String type, String fileName, String fileUrl, LocalDate validUntil) {
        return jdbc.sql("""
                        UPDATE document
                        SET type = :type, file_name = :name, file_url = :url, valid_until = :valid,
                            expiry_notified_at = NULL, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .param("type", type)
                .param("name", fileName)
                .param("url", fileUrl)
                .param("valid", validUntil)
                .update() == 1;
    }

    boolean anonymize(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        UPDATE document
                        SET anonymized_at = now(), updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .update() == 1;
    }

    record ExpiringRow(UUID documentId, UUID athleteId, String athleteName, String athleteEmail, String type, LocalDate validUntil) {
    }

    List<ExpiringRow> listExpiring(UUID tenantId, int days) {
        return jdbc.sql("""
                        SELECT d.id, d.athlete_id, a.full_name, a.email, d.type, d.valid_until
                        FROM document d
                        JOIN athlete a ON a.tenant_id = d.tenant_id AND a.id = d.athlete_id
                        WHERE d.tenant_id = :t AND d.anonymized_at IS NULL AND a.anonymized_at IS NULL
                          AND d.valid_until IS NOT NULL
                          AND d.valid_until <= (CURRENT_DATE + :days)
                          AND d.valid_until >= CURRENT_DATE
                          AND (d.expiry_notified_at IS NULL
                               OR d.expiry_notified_at < (now() - interval '7 days'))
                        """)
                .param("t", tenantId)
                .param("days", days)
                .query((rs, i) -> new ExpiringRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("athlete_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("type"),
                        rs.getObject("valid_until", java.sql.Date.class).toLocalDate()))
                .list();
    }

    /** Marca o documento como já notificado, para não repetir o e-mail no dia seguinte. */
    boolean markExpiryNotified(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        UPDATE document SET expiry_notified_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .update() == 1;
    }

    boolean athleteExists(UUID tenantId, UUID athleteId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM athlete WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL)
                        """)
                .param("tenant", tenantId)
                .param("id", athleteId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID entityId) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)
                        VALUES (:tenant, :actor, :action, 'document', :entity)
                        """)
                .param("tenant", tenantId)
                .param("actor", actorId)
                .param("action", action)
                .param("entity", entityId)
                .update();
    }

    private static DocumentRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DocumentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("athlete_id", UUID.class),
                rs.getString("type"),
                rs.getString("file_name"),
                rs.getString("file_url"),
                rs.getObject("valid_until", java.sql.Date.class) == null ? null
                        : rs.getObject("valid_until", java.sql.Date.class).toLocalDate(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}