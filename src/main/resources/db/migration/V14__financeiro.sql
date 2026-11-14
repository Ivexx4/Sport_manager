-- Módulo financeiro (fase 3): planos de quotas, cobranças, pagamentos e lançamentos contabilísticos.

CREATE TABLE fee_plan (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    name           text        NOT NULL,
    season_id      uuid,
    age_group_id   uuid,
    amount_cents   bigint      NOT NULL CHECK (amount_cents >= 0),
    periodicity    text        NOT NULL DEFAULT 'MONTHLY'
                   CHECK (periodicity IN ('ONE_TIME', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    anonymized_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, season_id) REFERENCES season (tenant_id, id) ON DELETE SET NULL,
    FOREIGN KEY (tenant_id, age_group_id) REFERENCES age_group (tenant_id, id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX fee_plan_tenant_name_uk
    ON fee_plan (tenant_id, lower(name)) WHERE anonymized_at IS NULL;

ALTER TABLE fee_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fee_plan
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON fee_plan TO volei_app;

CREATE TABLE charge (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    athlete_id    uuid        NOT NULL,
    guardian_id   uuid,
    fee_plan_id   uuid,
    amount_cents  bigint      NOT NULL CHECK (amount_cents >= 0),
    due_date      date        NOT NULL,
    status        text        NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'PAID', 'OVERDUE', 'CANCELLED')),
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, athlete_id) REFERENCES athlete (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (guardian_id) REFERENCES app_user (id) ON DELETE SET NULL,
    FOREIGN KEY (tenant_id, fee_plan_id) REFERENCES fee_plan (tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX charge_tenant_athlete_ix ON charge (tenant_id, athlete_id);
CREATE INDEX charge_tenant_status_ix ON charge (tenant_id, status);
CREATE INDEX charge_tenant_due_ix ON charge (tenant_id, due_date);

ALTER TABLE charge ENABLE ROW LEVEL SECURITY;
ALTER TABLE charge FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON charge
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON charge TO volei_app;

CREATE TABLE payment (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    charge_id     uuid        NOT NULL,
    method        text        NOT NULL DEFAULT 'CASH'
                  CHECK (method IN ('CASH', 'MULTIBANCO', 'MBWAY', 'TRANSFER', 'OTHER')),
    amount_cents  bigint      NOT NULL CHECK (amount_cents >= 0),
    reference     text,
    paid_at       timestamptz NOT NULL DEFAULT now(),
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, charge_id) REFERENCES charge (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX payment_tenant_charge_ix ON payment (tenant_id, charge_id);

ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON payment TO volei_app;

CREATE TABLE ledger_entry (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    type          text        NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    category      text        NOT NULL,
    amount_cents  bigint      NOT NULL CHECK (amount_cents >= 0),
    entry_date    date        NOT NULL,
    description   text,
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    UNIQUE (tenant_id, id)
);

CREATE INDEX ledger_entry_tenant_date_ix ON ledger_entry (tenant_id, entry_date);

ALTER TABLE ledger_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger_entry
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON ledger_entry TO volei_app;