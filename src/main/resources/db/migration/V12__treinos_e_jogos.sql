-- Treinos e jogos (fase 2): locais (pavilhões/campos), sessões de treino, presenças,
-- jogos, resultados por set e convocatórias. Seguem a convenção: tenant_id, created_at,
-- updated_at, created_by, RLS forçada e remoção não destrutiva (anonymized_at).

-- --- Locais (pavilhões/campos) ---------------------------------------------------------------

CREATE TABLE venue (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    name          text        NOT NULL,
    address       text,
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id)
);

CREATE UNIQUE INDEX venue_tenant_name_uk
    ON venue (tenant_id, lower(name)) WHERE anonymized_at IS NULL;

ALTER TABLE venue ENABLE ROW LEVEL SECURITY;
ALTER TABLE venue FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON venue
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON venue TO volei_app;

-- --- Sessões de treino -----------------------------------------------------------------------

CREATE TABLE training_session (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    team_id       uuid        NOT NULL,
    court_id      uuid,
    start_time    timestamptz NOT NULL,
    end_time      timestamptz NOT NULL,
    recurrence    text,
    notes         text,
    status        text        NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'CANCELLED', 'COMPLETED')),
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id),
    CHECK (end_time > start_time),
    FOREIGN KEY (tenant_id, team_id) REFERENCES team (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, court_id) REFERENCES venue (tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX training_session_tenant_team_ix ON training_session (tenant_id, team_id);
CREATE INDEX training_session_tenant_start_ix ON training_session (tenant_id, start_time);

ALTER TABLE training_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE training_session FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON training_session
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON training_session TO volei_app;

-- --- Presenças -------------------------------------------------------------------------------

CREATE TABLE attendance (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    session_id     uuid        NOT NULL,
    athlete_id     uuid        NOT NULL,
    status         text        NOT NULL DEFAULT 'PRESENT'
                   CHECK (status IN ('PRESENT', 'ABSENT', 'JUSTIFIED', 'LATE')),
    justification  text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    UNIQUE (tenant_id, session_id, athlete_id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES training_session (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, athlete_id) REFERENCES athlete (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX attendance_tenant_session_ix ON attendance (tenant_id, session_id);

ALTER TABLE attendance ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON attendance
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON attendance TO volei_app;

-- --- Jogos -----------------------------------------------------------------------------------

CREATE TABLE match (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    team_id       uuid        NOT NULL,
    competition   text,
    opponent      text        NOT NULL,
    location      text,
    court_id      uuid,
    match_date    timestamptz NOT NULL,
    is_home       boolean     NOT NULL DEFAULT true,
    status        text        NOT NULL DEFAULT 'SCHEDULED'
                  CHECK (status IN ('SCHEDULED', 'PLAYED', 'CANCELLED', 'POSTPONED')),
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, team_id) REFERENCES team (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, court_id) REFERENCES venue (tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX match_tenant_team_ix ON match (tenant_id, team_id);
CREATE INDEX match_tenant_date_ix ON match (tenant_id, match_date);

ALTER TABLE match ENABLE ROW LEVEL SECURITY;
ALTER TABLE match FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON match
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON match TO volei_app;

-- --- Resultados por set ----------------------------------------------------------------------

CREATE TABLE set_score (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    match_id         uuid        NOT NULL,
    set_number       int         NOT NULL CHECK (set_number > 0),
    our_points       int         NOT NULL CHECK (our_points >= 0),
    opponent_points  int         NOT NULL CHECK (opponent_points >= 0),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    UNIQUE (tenant_id, match_id, set_number),
    FOREIGN KEY (tenant_id, match_id) REFERENCES match (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE set_score ENABLE ROW LEVEL SECURITY;
ALTER TABLE set_score FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON set_score
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON set_score TO volei_app;

-- --- Convocatórias ---------------------------------------------------------------------------

CREATE TABLE call_up (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    match_id      uuid        NOT NULL,
    athlete_id    uuid        NOT NULL,
    status        text        NOT NULL DEFAULT 'CALLED_UP'
                  CHECK (status IN ('CALLED_UP', 'CONFIRMED', 'DECLINED', 'CANCELLED')),
    response      text        CHECK (response IS NULL OR response IN ('CONFIRMED', 'DECLINED')),
    responded_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, match_id, athlete_id),
    FOREIGN KEY (tenant_id, match_id) REFERENCES match (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, athlete_id) REFERENCES athlete (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX call_up_tenant_match_ix ON call_up (tenant_id, match_id);

ALTER TABLE call_up ENABLE ROW LEVEL SECURITY;
ALTER TABLE call_up FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON call_up
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON call_up TO volei_app;