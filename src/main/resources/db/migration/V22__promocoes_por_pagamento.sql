ALTER TABLE athlete_fee_override
    ADD COLUMN application_scope varchar(16) NOT NULL DEFAULT 'ALL',
    ADD COLUMN billing_period varchar(16);

ALTER TABLE athlete_fee_override
    ADD CONSTRAINT athlete_fee_override_scope_ck
    CHECK (application_scope IN ('ALL', 'SINGLE'));

ALTER TABLE athlete_fee_override
    ADD CONSTRAINT athlete_fee_override_single_period_ck
    CHECK (application_scope = 'ALL' OR billing_period IS NOT NULL);

CREATE INDEX athlete_fee_override_single_period_ix
    ON athlete_fee_override (tenant_id, athlete_id, fee_plan_id, billing_period)
    WHERE is_active AND application_scope = 'SINGLE';
