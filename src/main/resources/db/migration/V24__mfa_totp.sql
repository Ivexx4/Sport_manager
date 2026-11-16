-- MFA por TOTP (RFC 6238) na conta do utilizador.

ALTER TABLE app_user
    ADD COLUMN mfa_secret text,
    ADD COLUMN mfa_pending_at timestamptz,
    ADD COLUMN mfa_enabled_at timestamptz,
    ADD COLUMN mfa_last_used_step bigint;

COMMENT ON COLUMN app_user.mfa_secret IS
    'Segredo TOTP em Base32. Existe a partir do início da inscrição; a aplicação recusa-se a usá-lo enquanto mfa_enabled_at for nulo.';
COMMENT ON COLUMN app_user.mfa_pending_at IS 'Início da inscrição ainda não confirmada com um código válido.';
COMMENT ON COLUMN app_user.mfa_enabled_at IS 'Momento em que o MFA ficou ativo; nulo significa MFA desligado.';
COMMENT ON COLUMN app_user.mfa_last_used_step IS
    'Último passo TOTP (epoch/30s) aceite. Impede a reutilização do mesmo código dentro da janela de validade.';

-- Códigos de recuperação: tabela de plataforma (sem tenant_id/RLS), porque o MFA pertence à conta e
-- não ao clube — os códigos têm de servir em qualquer clube onde a pessoa entre. Guarda-se só o hash;
-- o código em claro é mostrado uma única vez, no momento em que é gerado.
CREATE TABLE mfa_recovery_code (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash  text        NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, code_hash)
);

CREATE INDEX mfa_recovery_code_user_ix ON mfa_recovery_code (user_id) WHERE used_at IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON mfa_recovery_code TO volei_app;
