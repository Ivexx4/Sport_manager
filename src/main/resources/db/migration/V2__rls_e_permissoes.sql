-- Row Level Security e permissões do papel da aplicação (volei_app).
-- Os papéis são criados fora das migrações (ver docker/postgres-init/01-roles.sql).

-- Tenant da transação atual. Devolve NULL se não estiver definido (ou estiver vazio):
-- nesse caso a comparação "tenant_id = NULL" nunca é verdadeira e nenhuma linha é visível.
CREATE FUNCTION app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

-- ENABLE + FORCE: a política aplica-se também ao dono das tabelas.
ALTER TABLE membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON membership
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE athlete ENABLE ROW LEVEL SECURITY;
ALTER TABLE athlete FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON athlete
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- Permissões mínimas para a aplicação.
GRANT EXECUTE ON FUNCTION app_current_tenant() TO volei_app;

-- Tabelas da plataforma: a aplicação só lê tenants.
GRANT SELECT ON tenant TO volei_app;
GRANT SELECT, INSERT, UPDATE ON app_user TO volei_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON membership TO volei_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON athlete TO volei_app;

-- Auditoria imutável: sem UPDATE nem DELETE para a aplicação.
GRANT SELECT, INSERT ON audit_log TO volei_app;
