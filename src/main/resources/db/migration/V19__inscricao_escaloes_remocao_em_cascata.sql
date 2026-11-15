-- Os registos de inscrição não podem sobreviver ao escalão que identificam.
-- Isto também permite a limpeza de dados de teste e a remoção administrativa
-- não destrutiva do agregado de época/escalão.
ALTER TABLE athlete_season_registration
    DROP CONSTRAINT athlete_season_registration_tenant_id_age_group_id_fkey;

ALTER TABLE athlete_season_registration
    ADD CONSTRAINT athlete_season_registration_tenant_id_age_group_id_fkey
    FOREIGN KEY (tenant_id, age_group_id) REFERENCES age_group (tenant_id, id) ON DELETE CASCADE;
