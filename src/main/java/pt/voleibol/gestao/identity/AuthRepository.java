package pt.voleibol.gestao.identity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acesso a dados da autenticação. Corre sempre dentro de uma transação, por isso as tabelas com RLS
 * só mostram o tenant atual; os filtros por tenant_id são uma segunda barreira, não a primeira.
 */
@Repository
class AuthRepository {

    record UserRow(UUID id, String passwordHash) {
    }

    record RefreshRow(UUID id, UUID userId, Instant createdAt, Instant expiresAt, boolean revoked) {
    }

    private final JdbcClient jdbc;

    AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** app_user é da plataforma com RLS por membership/relação ao tenant: visível apenas no contexto do clube. */
    Optional<UserRow> findActiveUserByEmail(String email) {
        return jdbc.sql("""
                        SELECT id, password_hash FROM app_user
                        WHERE lower(email) = lower(:email) AND status = 'ACTIVE'
                        """)
                .param("email", email)
                .query((rs, i) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("password_hash")))
                .optional();
    }

    Set<String> findActiveRoles(UUID tenantId, UUID userId) {
        return Set.copyOf(jdbc.sql("""
                        SELECT role FROM membership
                        WHERE tenant_id = :tenant AND user_id = :user AND status = 'ACTIVE'
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .query(String.class)
                .list());
    }

    void insertRefreshToken(UUID tenantId, UUID userId, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO refresh_token (tenant_id, user_id, token_hash, expires_at)
                        VALUES (:tenant, :user, :hash, :expires)
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("hash", tokenHash)
                .param("expires", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update();
    }

    Optional<RefreshRow> findRefreshToken(UUID tenantId, String tokenHash) {
        return jdbc.sql("""
                        SELECT id, user_id, created_at, expires_at, revoked_at IS NOT NULL AS revoked
                        FROM refresh_token
                        WHERE token_hash = :hash AND tenant_id = :tenant
                        """)
                .param("hash", tokenHash)
                .param("tenant", tenantId)
                .query((rs, i) -> new RefreshRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("revoked")))
                .optional();
    }

    /** Momento da última mudança de password (1970 se nunca mudou): sessões anteriores deixam de servir. */
    Instant findPasswordChangedAt(UUID userId) {
        return jdbc.sql("""
                        SELECT coalesce(password_changed_at, 'epoch'::timestamptz)
                        FROM app_user WHERE id = :id
                        """)
                .param("id", userId)
                .query((rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .optional()
                .orElse(Instant.EPOCH);
    }

    /** Revoga só se ainda estiver ativo. Devolve false se outro pedido chegou primeiro. */
    boolean revokeIfActive(UUID tenantId, UUID tokenId) {
        return jdbc.sql("""
                        UPDATE refresh_token SET revoked_at = now()
                        WHERE id = :id AND tenant_id = :tenant AND revoked_at IS NULL
                        """)
                .param("id", tokenId)
                .param("tenant", tenantId)
                .update() == 1;
    }

    void revokeAllForUser(UUID tenantId, UUID userId) {
        jdbc.sql("""
                        UPDATE refresh_token SET revoked_at = now()
                        WHERE tenant_id = :tenant AND user_id = :user AND revoked_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .update();
    }

    void audit(UUID tenantId, UUID userId, String action) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)
                        VALUES (:tenant, :user, :action, 'session', :user)
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("action", action)
                .update();
    }
}
