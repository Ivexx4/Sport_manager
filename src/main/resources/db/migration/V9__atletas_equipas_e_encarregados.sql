-- Fase 1: atletas com âmbito por recurso, encarregados de educação e base mínima de equipas.
-- A base de equipas é intencionalmente pequena: o módulo teams (épocas, escalões, plantéis e staff)
-- alarga-a mais tarde sem mudar as relações aqui criadas.

-- --- Atletas ---------------------------------------------------------------------------------

-- Conta de utilizador do próprio atleta (opcional): permite ao papel ATLETA ver/alterar o seu registo.
ALTER TABLE athlete ADD COLUMN user_id uuid REFERENCES app_user (id);
ALTER TABLE athlete ADD COLUMN status text NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE', 'INACTIVE'));

CREATE UNIQUE INDEX athlete_tenant_user_uk
    ON athlete (tenant_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX athlete_tenant_status_ix ON athlete (tenant_id, status);

-- --- Encarregados de educação ----------------------------------------------------------------

CREATE TABLE athlete_guardian (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    athlete_id   uuid        NOT NULL,
    user_id      uuid        NOT NULL REFERENCES app_user (id),
    relationship text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, athlete_id, user_id),
    FOREIGN KEY (tenant_id, athlete_id) REFERENCES athlete (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX athlete_guardian_tenant_user_ix ON athlete_guardian (tenant_id, user_id);

ALTER TABLE athlete_guardian ENABLE ROW LEVEL SECURITY;
ALTER TABLE athlete_guardian FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON athlete_guardian
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON athlete_guardian TO volei_app;

-- --- Equipas (base mínima) -------------------------------------------------------------------

CREATE TABLE team (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    name       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE'
               CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    UNIQUE (tenant_id, id)
);

CREATE UNIQUE INDEX team_tenant_name_uk ON team (tenant_id, lower(name));

ALTER TABLE team ENABLE ROW LEVEL SECURITY;
ALTER TABLE team FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON team
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON team TO volei_app;

-- Treinadores (e adjuntos) de cada equipa. É esta relação que define o âmbito do papel TREINADOR.
CREATE TABLE team_staff (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    team_id    uuid        NOT NULL,
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    role       text        NOT NULL DEFAULT 'TREINADOR'
               CHECK (role IN ('TREINADOR', 'TREINADOR_ADJUNTO')),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    UNIQUE (tenant_id, team_id, user_id),
    FOREIGN KEY (tenant_id, team_id) REFERENCES team (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX team_staff_tenant_user_ix ON team_staff (tenant_id, user_id);

ALTER TABLE team_staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE team_staff FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON team_staff
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON team_staff TO volei_app;

-- Plantel: atletas de cada equipa. Define os atletas que o treinador dessa equipa pode gerir.
CREATE TABLE team_athlete (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    team_id    uuid        NOT NULL,
    athlete_id uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    UNIQUE (tenant_id, team_id, athlete_id),
    FOREIGN KEY (tenant_id, team_id) REFERENCES team (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, athlete_id) REFERENCES athlete (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX team_athlete_tenant_athlete_ix ON team_athlete (tenant_id, athlete_id);

ALTER TABLE team_athlete ENABLE ROW LEVEL SECURITY;
ALTER TABLE team_athlete FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON team_athlete
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON team_athlete TO volei_app;
