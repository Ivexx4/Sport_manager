-- Documentos com validade (cartão de cidadão, exame médico, ...) por atleta.
-- Seguem a convenção: tenant_id, created_at, updated_at, created_by, RLS forçada e
-- remoção não destrutiva (anonymized_at).

CREATE TABLE document (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    athlete_id    uuid        NOT NULL,
    type          text        NOT NULL,
    file_name     text,
    file_url      text,
    valid_until   date,
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, athlete_id) REFERENCES athlete (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX document_tenant_athlete_ix ON document (tenant_id, athlete_id);
CREATE INDEX document_tenant_valid_until_ix ON document (tenant_id, valid_until) WHERE anonymized_at IS NULL;

ALTER TABLE document ENABLE ROW LEVEL SECURITY;
ALTER TABLE document FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON document TO volei_app;