-- Promoções individuais podem permanecer ativas indefinidamente e ser
-- desativadas sem apagar o histórico.
ALTER TABLE athlete_fee_override
    ADD COLUMN is_active boolean NOT NULL DEFAULT true;

CREATE INDEX athlete_fee_override_active_ix
    ON athlete_fee_override (tenant_id, athlete_id, fee_plan_id, valid_from)
    WHERE is_active;
