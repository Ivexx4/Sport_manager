-- Épocas (FPV) e escalões por época. Seguem a convenção das restantes tabelas de negócio:
-- tenant_id, created_at, updated_at, created_by, RLS forçada e remoção não destrutiva (anonymized_at).

-- --- Épocas ----------------------------------------------------------------------------------

CREATE TABLE season (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    name          text        NOT NULL,
    start_date    date,
    end_date      date,
    is_active     boolean     NOT NULL DEFAULT true,
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id),
    CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date)
);

CREATE UNIQUE INDEX season_tenant_name_uk
    ON season (tenant_id, lower(name)) WHERE anonymized_at IS NULL;
CREATE INDEX season_tenant_is_active_ix
    ON season (tenant_id, is_active) WHERE anonymized_at IS NULL;

ALTER TABLE season ENABLE ROW LEVEL SECURITY;
ALTER TABLE season FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON season
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON season TO volei_app;

-- --- Escalões --------------------------------------------------------------------------------

CREATE TABLE age_group (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    season_id        uuid        NOT NULL,
    name             text        NOT NULL,
    min_birth_year  int         NOT NULL,
    max_birth_year  int         NOT NULL,
    anonymized_at   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    UNIQUE (tenant_id, id),
    CHECK (max_birth_year >= min_birth_year),
    FOREIGN KEY (tenant_id, season_id) REFERENCES season (tenant_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX age_group_tenant_season_name_uk
    ON age_group (tenant_id, season_id, lower(name)) WHERE anonymized_at IS NULL;
CREATE INDEX age_group_tenant_season_ix ON age_group (tenant_id, season_id) WHERE anonymized_at IS NULL;

ALTER TABLE age_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE age_group FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON age_group
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON age_group TO volei_app;

-- --- Autorizações excepcionais de subida de escalão -----------------------------------------

CREATE TABLE season_promotion (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    season_id     uuid        NOT NULL,
    athlete_id    uuid        NOT NULL,
    age_group_id  uuid        NOT NULL,
    authorized_by uuid,
    status        text        NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'REVOKED')),
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, season_id, athlete_id, age_group_id),
    -- chaves estrangeiras compostas (tenant_id, id) nos alvos, no padrão da plataforma
    FOREIGN KEY (tenant_id, season_id) REFERENCES season (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, athlete_id) REFERENCES athlete (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, age_group_id) REFERENCES age_group (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX season_promotion_tenant_athlete_ix ON season_promotion (tenant_id, athlete_id);

ALTER TABLE season_promotion ENABLE ROW LEVEL SECURITY;
ALTER TABLE season_promotion FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON season_promotion
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON season_promotion TO volei_app;

-- --- Associação às equipas -----------------------------------------------------------------

-- Nulos para não quebrar as equipas existentes; a chave composta garante que a época e o escalão
-- pertencem ao mesmo clube da equipa.
ALTER TABLE team ADD COLUMN season_id uuid;
ALTER TABLE team ADD COLUMN age_group_id uuid;

ALTER TABLE team ADD CONSTRAINT team_season_fk
    FOREIGN KEY (tenant_id, season_id) REFERENCES season (tenant_id, id) ON DELETE SET NULL;
ALTER TABLE team ADD CONSTRAINT team_age_group_fk
    FOREIGN KEY (tenant_id, age_group_id) REFERENCES age_group (tenant_id, id) ON DELETE SET NULL;

CREATE INDEX team_tenant_season_ix ON team (tenant_id, season_id) WHERE season_id IS NOT NULL;
CREATE INDEX team_tenant_age_group_ix ON team (tenant_id, age_group_id) WHERE age_group_id IS NOT NULL;
