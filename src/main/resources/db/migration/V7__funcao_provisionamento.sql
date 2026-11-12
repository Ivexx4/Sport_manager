-- Provisionamento de um novo clube com o primeiro administrador.
-- Executa com privilégios do dono (volei_migrator) para permitir criar o utilizador inicial
-- mesmo com RLS em app_user; a chamada deve ser protegida ao nível da aplicação (chave de bootstrap).
-- O papel volei_migrator deve ter BYPASSRLS (definido no init/docker).

CREATE OR REPLACE FUNCTION provision_tenant(
    p_slug text,
    p_name text,
    p_admin_email text,
    p_admin_name text,
    p_admin_password_hash text
) RETURNS TABLE(tenant_id uuid, admin_user_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
#variable_conflict use_column
DECLARE
    v_tenant_id uuid;
    v_user_id uuid;
BEGIN
    INSERT INTO tenant (name, slug)
    VALUES (p_name, p_slug)
    RETURNING id INTO v_tenant_id;

    INSERT INTO app_user (email, name, password_hash, password_changed_at, status)
    VALUES (lower(p_admin_email), p_admin_name, p_admin_password_hash, now(), 'ACTIVE')
    ON CONFLICT ((lower(email))) DO UPDATE
        SET name = EXCLUDED.name,
            password_hash = COALESCE(app_user.password_hash, EXCLUDED.password_hash),
            password_changed_at = COALESCE(app_user.password_changed_at, EXCLUDED.password_changed_at),
            updated_at = now()
    RETURNING id INTO v_user_id;

    INSERT INTO membership (tenant_id, user_id, role, status)
    VALUES (v_tenant_id, v_user_id, 'ADMIN', 'ACTIVE')
    ON CONFLICT (tenant_id, user_id, role) DO UPDATE SET status = 'ACTIVE', updated_at = now();

    tenant_id := v_tenant_id;
    admin_user_id := v_user_id;
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION provision_tenant(text, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION provision_tenant(text, text, text, text, text) TO volei_app;
