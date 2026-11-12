package pt.voleibol.gestao.identity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Convites, tokens de reposição de password e dados de conta. As tabelas com RLS (incluindo app_user,
 * por membership/relação ao tenant) só mostram dados acessíveis no tenant atual; os filtros por tenant_id
 * são uma segunda barreira.
 */
@Repository
class AccountRepository {

    record TenantInfo(String slug, String name) {
    }

    record UserBasic(UUID id, String status) {
    }

    record Invitation(UUID id, String email, String name, String roles, UUID invitedBy,
                      Instant expiresAt, boolean accepted, boolean revoked) {
    }

    record ResetToken(UUID id, UUID userId, Instant expiresAt, boolean used) {
    }

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // --- clube e utilizadores ---

    Optional<TenantInfo> findTenant(UUID tenantId) {
        return jdbc.sql("SELECT slug, name FROM tenant WHERE id = :id")
                .param("id", tenantId)
                .query((rs, i) -> new TenantInfo(rs.getString("slug"), rs.getString("name")))
                .optional();
    }

    /** Qualquer estado: quem está desativado não pode aceitar convites nem repor a password. */
    Optional<UserBasic> findUserByEmail(String email) {
        return jdbc.sql("SELECT id, status FROM app_user WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query((rs, i) -> new UserBasic(rs.getObject("id", UUID.class), rs.getString("status")))
                .optional();
    }

    /** Devolve vazio se o email já existir (corrida entre dois pedidos). */
    Optional<UUID> insertUserIfAbsent(String email, String name, String passwordHash) {
        return jdbc.sql("""
                        INSERT INTO app_user (email, name, password_hash, password_changed_at)
                        VALUES (lower(:email), :name, :hash, now())
                        ON CONFLICT ((lower(email))) DO NOTHING
                        RETURNING id
                        """)
                .param("email", email)
                .param("name", name)
                .param("hash", passwordHash)
                .query(UUID.class)
                .optional();
    }

    /** Palavra vazia significa que o utilizador não tem password definida. */
    String findPasswordHash(UUID userId) {
        return jdbc.sql("SELECT coalesce(password_hash, '') FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElse("");
    }

    /** Só para utilizadores ativos. Regista o momento, para invalidar as sessões anteriores. */
    boolean setPassword(UUID userId, String passwordHash) {
        return jdbc.sql("""
                        UPDATE app_user
                        SET password_hash = :hash, password_changed_at = now(), updated_at = now()
                        WHERE id = :id AND status = 'ACTIVE'
                        """)
                .param("hash", passwordHash)
                .param("id", userId)
                .update() == 1;
    }

    void upsertMembership(UUID tenantId, UUID userId, String role, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO membership (tenant_id, user_id, role, status, created_by)
                        VALUES (:tenant, :user, :role, 'ACTIVE', :by)
                        ON CONFLICT (tenant_id, user_id, role)
                        DO UPDATE SET status = 'ACTIVE', updated_at = now()
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("role", role)
                .param("by", createdBy)
                .update();
    }

    // --- convites ---

    void revokePendingInvitations(UUID tenantId, String email) {
        jdbc.sql("""
                        UPDATE invitation SET revoked_at = now()
                        WHERE tenant_id = :tenant AND lower(email) = lower(:email)
                          AND accepted_at IS NULL AND revoked_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("email", email)
                .update();
    }

    void insertInvitation(UUID tenantId, String email, String name, String roles, String tokenHash,
                          UUID invitedBy, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO invitation (tenant_id, email, name, roles, token_hash, invited_by, expires_at)
                        VALUES (:tenant, :email, :name, :roles, :hash, :by, :expires)
                        """)
                .param("tenant", tenantId)
                .param("email", email)
                .param("name", name)
                .param("roles", roles)
                .param("hash", tokenHash)
                .param("by", invitedBy)
                .param("expires", utc(expiresAt))
                .update();
    }

    Optional<Invitation> findInvitation(UUID tenantId, String tokenHash) {
        return jdbc.sql("""
                        SELECT id, email, name, roles, invited_by, expires_at,
                               accepted_at IS NOT NULL AS accepted, revoked_at IS NOT NULL AS revoked
                        FROM invitation
                        WHERE token_hash = :hash AND tenant_id = :tenant
                        """)
                .param("hash", tokenHash)
                .param("tenant", tenantId)
                .query((rs, i) -> new Invitation(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("name"),
                        rs.getString("roles"),
                        rs.getObject("invited_by", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("accepted"),
                        rs.getBoolean("revoked")))
                .optional();
    }

    /** Só marca se ainda estiver pendente: dois pedidos em simultâneo não aceitam o mesmo convite. */
    boolean markInvitationAccepted(UUID tenantId, UUID invitationId) {
        return jdbc.sql("""
                        UPDATE invitation SET accepted_at = now()
                        WHERE id = :id AND tenant_id = :tenant
                          AND accepted_at IS NULL AND revoked_at IS NULL
                        """)
                .param("id", invitationId)
                .param("tenant", tenantId)
                .update() == 1;
    }

    // --- gestão de membros ---

    record MemberRole(String role, String status) {
    }

    record ClubMember(UUID userId, String email, String name, String userStatus) {
    }

    java.util.List<ClubMember> listMembers(UUID tenantId) {
        return jdbc.sql("""
                        SELECT DISTINCT u.id, u.email, u.name, u.status
                        FROM app_user u
                        JOIN membership m ON m.user_id = u.id
                        WHERE m.tenant_id = :tenant
                        ORDER BY u.name, u.email
                        """)
                .param("tenant", tenantId)
                .query((rs, i) -> new ClubMember(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("name"),
                        rs.getString("status")))
                .list();
    }

    java.util.List<MemberRole> listRoles(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        SELECT role, status FROM membership
                        WHERE tenant_id = :tenant AND user_id = :user
                        ORDER BY role
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .query((rs, i) -> new MemberRole(rs.getString("role"), rs.getString("status")))
                .list();
    }

    java.util.Set<String> findRoles(UUID tenantId, UUID userId) {
        return java.util.Set.copyOf(jdbc.sql("""
                        SELECT role FROM membership
                        WHERE tenant_id = :tenant AND user_id = :user
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .query(String.class)
                .list());
    }

    int disableAllMemberships(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        UPDATE membership SET status = 'DISABLED', updated_at = now()
                        WHERE tenant_id = :tenant AND user_id = :user AND status = 'ACTIVE'
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .update();
    }

    boolean removeRole(UUID tenantId, UUID userId, String role) {
        return jdbc.sql("""
                        DELETE FROM membership
                        WHERE tenant_id = :tenant AND user_id = :user AND role = :role
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("role", role)
                .update() == 1;
    }

    int countActiveAdmins(UUID tenantId) {
        return jdbc.sql("""
                        SELECT count(DISTINCT user_id) FROM membership
                        WHERE tenant_id = :tenant AND role = 'ADMIN' AND status = 'ACTIVE'
                        """)
                .param("tenant", tenantId)
                .query(Integer.class)
                .single();
    }

    boolean hasActiveRole(UUID tenantId, UUID userId, String role) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM membership
                            WHERE tenant_id = :tenant AND user_id = :user AND role = :role AND status = 'ACTIVE'
                        )
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("role", role)
                .query(Boolean.class)
                .single();
    }

    // --- reposição de password ---

    int countResetTokensSince(UUID tenantId, UUID userId, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM password_reset_token
                        WHERE tenant_id = :tenant AND user_id = :user AND created_at >= :since
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("since", utc(since))
                .query(Integer.class)
                .single();
    }

    void insertResetToken(UUID tenantId, UUID userId, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO password_reset_token (tenant_id, user_id, token_hash, expires_at)
                        VALUES (:tenant, :user, :hash, :expires)
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("hash", tokenHash)
                .param("expires", utc(expiresAt))
                .update();
    }

    Optional<ResetToken> findResetToken(UUID tenantId, String tokenHash) {
        return jdbc.sql("""
                        SELECT id, user_id, expires_at, used_at IS NOT NULL AS used
                        FROM password_reset_token
                        WHERE token_hash = :hash AND tenant_id = :tenant
                        """)
                .param("hash", tokenHash)
                .param("tenant", tenantId)
                .query((rs, i) -> new ResetToken(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("used")))
                .optional();
    }

    boolean markResetTokenUsed(UUID tenantId, UUID tokenId) {
        return jdbc.sql("""
                        UPDATE password_reset_token SET used_at = now()
                        WHERE id = :id AND tenant_id = :tenant AND used_at IS NULL
                        """)
                .param("id", tokenId)
                .param("tenant", tenantId)
                .update() == 1;
    }

    /** Depois de repor a password, os outros links pendentes do utilizador deixam de servir. */
    void invalidateResetTokens(UUID tenantId, UUID userId) {
        jdbc.sql("""
                        UPDATE password_reset_token SET used_at = now()
                        WHERE tenant_id = :tenant AND user_id = :user AND used_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .update();
    }
}
