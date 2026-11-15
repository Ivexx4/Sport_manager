package pt.voleibol.gestao.shared;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Leitura de colunas e escrita na auditoria, para evitar repetir o mesmo mapeamento JDBC
 * em todos os repositórios.
 */
public final class Db {

    private Db() {
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    /** Data opcional: devolve {@code null} quando a coluna é nula. */
    public static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getObject(column, java.sql.Date.class);
        return value == null ? null : value.toLocalDate();
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** Resultado de um {@code SELECT EXISTS (...)}. */
    public static boolean exists(JdbcClient.StatementSpec spec) {
        return Boolean.TRUE.equals(spec.query(Boolean.class).single());
    }

    public static void audit(JdbcClient jdbc, UUID tenantId, UUID actorId, String action, String entity,
                             UUID entityId) {
        jdbc.sql("INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)"
                        + " VALUES (:t,:a,:act,:e,:id)")
                .param("t", tenantId).param("a", actorId).param("act", action).param("e", entity)
                .param("id", entityId).update();
    }
}
