-- Geração automática de quotas a partir dos planos (fase 3).
-- O período faturado fica gravado na cobrança. O índice único parcial é a chave de idempotência:
-- gerar duas vezes o mesmo período para o mesmo plano/atleta não cria cobranças duplicadas.
ALTER TABLE charge ADD COLUMN billing_period text;

ALTER TABLE charge ADD CONSTRAINT charge_billing_period_chk
    CHECK (billing_period IS NULL OR billing_period ~ '^[0-9]{4}(-[0-9]{2}|-Q[1-4])?$');

CREATE UNIQUE INDEX charge_tenant_plan_athlete_period_uk
    ON charge (tenant_id, fee_plan_id, athlete_id, billing_period)
    WHERE fee_plan_id IS NOT NULL AND billing_period IS NOT NULL AND anonymized_at IS NULL;
