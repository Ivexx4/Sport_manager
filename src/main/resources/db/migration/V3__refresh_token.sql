-- Refresh tokens opacos. Só se guarda o hash SHA-256: um dump da base de dados não permite usá-los.

CREATE TABLE refresh_token (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    user_id     uuid        NOT NULL REFERENCES app_user (id),
    token_hash  text        NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX refresh_token_tenant_user_ix ON refresh_token (tenant_id, user_id);

ALTER TABLE refresh_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_token FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refresh_token
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- DELETE para a limpeza periódica de tokens expirados.
GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_token TO volei_app;
