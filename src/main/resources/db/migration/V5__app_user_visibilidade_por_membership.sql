-- Migrar visibilidade de app_user para modelo por membership.
-- app_user passa a ter RLS: só é visível/alterável se houver relação com o tenant atual
-- (membership ativo, convite pendente válido, token de reposição ou refresh token existente).

ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;

CREATE POLICY app_user_tenant_visibility ON app_user
    USING (
        EXISTS (
            SELECT 1 FROM membership m
            WHERE m.user_id = app_user.id
              AND m.tenant_id = app_current_tenant()
        )
        OR EXISTS (
            SELECT 1 FROM invitation i
            WHERE lower(i.email) = lower(app_user.email)
              AND i.tenant_id = app_current_tenant()
              AND i.accepted_at IS NULL
              AND i.revoked_at IS NULL
              AND i.expires_at > now()
        )
        OR EXISTS (
            SELECT 1 FROM password_reset_token prt
            WHERE prt.user_id = app_user.id
              AND prt.tenant_id = app_current_tenant()
        )
        OR EXISTS (
            SELECT 1 FROM refresh_token rt
            WHERE rt.user_id = app_user.id
              AND rt.tenant_id = app_current_tenant()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM membership m
            WHERE m.user_id = app_user.id
              AND m.tenant_id = app_current_tenant()
        )
        OR EXISTS (
            SELECT 1 FROM invitation i
            WHERE lower(i.email) = lower(app_user.email)
              AND i.tenant_id = app_current_tenant()
              AND i.accepted_at IS NULL
              AND i.revoked_at IS NULL
              AND i.expires_at > now()
        )
        OR EXISTS (
            SELECT 1 FROM password_reset_token prt
            WHERE prt.user_id = app_user.id
              AND prt.tenant_id = app_current_tenant()
        )
        OR EXISTS (
            SELECT 1 FROM refresh_token rt
            WHERE rt.user_id = app_user.id
              AND rt.tenant_id = app_current_tenant()
        )
    );
