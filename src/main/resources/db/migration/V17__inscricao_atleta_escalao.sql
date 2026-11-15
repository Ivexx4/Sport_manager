-- Escalão em que o atleta está oficialmente inscrito na época.
CREATE TABLE athlete_season_registration (
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    season_id     uuid NOT NULL,
    athlete_id    uuid NOT NULL,
    age_group_id  uuid NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    PRIMARY KEY (tenant_id, season_id, athlete_id),
    FOREIGN KEY (tenant_id, season_id) REFERENCES season (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, athlete_id) REFERENCES athlete (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, age_group_id) REFERENCES age_group (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX athlete_season_registration_group_ix
    ON athlete_season_registration (tenant_id, season_id, age_group_id);

ALTER TABLE athlete_season_registration ENABLE ROW LEVEL SECURITY;
ALTER TABLE athlete_season_registration FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON athlete_season_registration
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON athlete_season_registration TO volei_app;
