-- Configuração extensível por modalidade, época e clube. As colunas são opcionais para
-- preservar os dados de instalações anteriores e permitir uma migração gradual.
CREATE TABLE modality (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    code           text NOT NULL,
    name           text NOT NULL,
    is_active      boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX modality_tenant_code_uk ON modality (tenant_id, lower(code));

ALTER TABLE season ADD COLUMN modality_id uuid;
ALTER TABLE season ADD COLUMN cutoff_date date;
ALTER TABLE season ADD CONSTRAINT season_modality_fk
    FOREIGN KEY (tenant_id, modality_id) REFERENCES modality (tenant_id, id) ON DELETE SET NULL;

ALTER TABLE age_group ADD COLUMN modality_id uuid;
ALTER TABLE age_group ADD COLUMN is_active boolean NOT NULL DEFAULT true;
ALTER TABLE age_group ADD CONSTRAINT age_group_modality_fk
    FOREIGN KEY (tenant_id, modality_id) REFERENCES modality (tenant_id, id) ON DELETE SET NULL;

ALTER TABLE team ADD COLUMN modality_id uuid;
ALTER TABLE team ADD CONSTRAINT team_modality_fk
    FOREIGN KEY (tenant_id, modality_id) REFERENCES modality (tenant_id, id) ON DELETE SET NULL;

ALTER TABLE fee_plan ADD COLUMN modality_id uuid;
ALTER TABLE fee_plan ADD COLUMN is_active boolean NOT NULL DEFAULT true;
ALTER TABLE fee_plan ADD COLUMN effective_from date;
ALTER TABLE fee_plan ADD COLUMN effective_to date;
ALTER TABLE fee_plan ADD CONSTRAINT fee_plan_effective_period_ck
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from);
ALTER TABLE fee_plan ADD CONSTRAINT fee_plan_modality_fk
    FOREIGN KEY (tenant_id, modality_id) REFERENCES modality (tenant_id, id) ON DELETE SET NULL;

-- Configuração do ciclo automático de épocas por clube/modalidade.
CREATE TABLE season_template (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    modality_id    uuid,
    name_pattern   text NOT NULL DEFAULT '{startYear}/{endYear}',
    start_month   smallint NOT NULL DEFAULT 9 CHECK (start_month BETWEEN 1 AND 12),
    start_day     smallint NOT NULL DEFAULT 1 CHECK (start_day BETWEEN 1 AND 31),
    end_month     smallint NOT NULL DEFAULT 6 CHECK (end_month BETWEEN 1 AND 12),
    end_day       smallint NOT NULL DEFAULT 30 CHECK (end_day BETWEEN 1 AND 31),
    cutoff_month  smallint NOT NULL DEFAULT 1 CHECK (cutoff_month BETWEEN 1 AND 12),
    cutoff_day    smallint NOT NULL DEFAULT 1 CHECK (cutoff_day BETWEEN 1 AND 31),
    auto_create   boolean NOT NULL DEFAULT true,
    is_active     boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, modality_id) REFERENCES modality (tenant_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX season_template_tenant_modality_uk
    ON season_template (tenant_id, modality_id) WHERE is_active;

-- Excepções individuais ao plano de quotas do clube.
CREATE TABLE athlete_fee_override (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    athlete_id     uuid NOT NULL,
    fee_plan_id    uuid,
    amount_cents   bigint NOT NULL CHECK (amount_cents >= 0),
    valid_from     date NOT NULL,
    valid_to       date,
    reason         text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    UNIQUE (tenant_id, id),
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    FOREIGN KEY (tenant_id, athlete_id) REFERENCES athlete (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, fee_plan_id) REFERENCES fee_plan (tenant_id, id) ON DELETE SET NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON modality, season_template, athlete_fee_override TO volei_app;
ALTER TABLE modality ENABLE ROW LEVEL SECURITY;
ALTER TABLE modality FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON modality USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE season_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE season_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON season_template USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE athlete_fee_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE athlete_fee_override FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON athlete_fee_override USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
