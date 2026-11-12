-- Convites de utilizadores e tokens de reposição de password.
-- Em ambos só se guarda o hash SHA-256 do token enviado por e-mail.

-- Momento da última mudança de password. Refresh tokens emitidos antes deixam de servir,
-- em todos os clubes (o RLS não deixa revogá-los diretamente a partir de outro clube).
ALTER TABLE app_user ADD COLUMN password_changed_at timestamptz;

CREATE TABLE invitation (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    email       text        NOT NULL,
    name        text        NOT NULL,
    roles       text        NOT NULL,                 -- papéis separados por vírgula, validados na aplicação
    token_hash  text        NOT NULL UNIQUE,
    invited_by  uuid        NOT NULL REFERENCES app_user (id),
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX invitation_tenant_email_ix ON invitation (tenant_id, lower(email));

CREATE TABLE password_reset_token (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    user_id     uuid        NOT NULL REFERENCES app_user (id),
    token_hash  text        NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX password_reset_token_tenant_user_ix
    ON password_reset_token (tenant_id, user_id, created_at DESC);

ALTER TABLE invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invitation
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE password_reset_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_token FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON password_reset_token
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON invitation TO volei_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON password_reset_token TO volei_app;
