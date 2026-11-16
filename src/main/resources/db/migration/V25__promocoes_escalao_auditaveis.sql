-- A autorização de subida de escalão passa a ser gerida pela API: guarda o motivo invocado e
-- permite listar as autorizações de uma época sem varrer a tabela toda.
ALTER TABLE season_promotion ADD COLUMN reason text;

CREATE INDEX season_promotion_season_ix
    ON season_promotion (tenant_id, season_id, age_group_id) WHERE anonymized_at IS NULL;

-- Autorizações criadas antes da API não têm autor explícito: herdam quem as inseriu.
UPDATE season_promotion SET authorized_by = created_by
WHERE authorized_by IS NULL AND created_by IS NOT NULL;
