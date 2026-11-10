-- V1: base de tenancy.
-- Regra do projeto: toda a tabela de negócio tem tenant_id, RLS ativa e FORÇADA,
-- política com USING + WITH CHECK, e grants explícitos ao papel da aplicação.

CREATE TABLE tenant (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        text        NOT NULL UNIQUE,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Tabela exemplo, a estender na fase 1 (atletas, encarregados, documentos...).
CREATE TABLE athlete (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    full_name   text        NOT NULL,
    birth_date  date,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- permite chaves estrangeiras compostas (tenant_id, id) noutras tabelas
    UNIQUE (tenant_id, id)
);

CREATE INDEX athlete_tenant_idx ON athlete (tenant_id, full_name);

ALTER TABLE athlete ENABLE ROW LEVEL SECURITY;
ALTER TABLE athlete FORCE ROW LEVEL SECURITY;

-- current_setting(..., true) devolve NULL quando a variável não existe:
-- NULL nunca é igual a nada, logo sem tenant definido não se vê nem escreve nada (falha fechada).
CREATE POLICY tenant_isolation ON athlete
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Grants ao papel da aplicação (se existir). Em produção o papel é criado antes das migrações.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clube_app') THEN
        -- A aplicação só lê tenants; criar/alterar clubes é função da plataforma (backoffice).
        GRANT SELECT ON tenant TO clube_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON athlete TO clube_app;
    END IF;
END
$$;
