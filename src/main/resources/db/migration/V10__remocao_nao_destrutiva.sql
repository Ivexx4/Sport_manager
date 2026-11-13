-- Remoção não destrutiva (RGPD): em vez de apagar registos de negócio, desativam-se e
-- anonimizam-se. As linhas mantêm-se para preservar chaves externas, histórico desportivo e
-- registos financeiros exigidos por lei.
--
-- Convenção para os próximos módulos:
--   * DELETE de uma entidade com histórico = status 'INACTIVE' + anonymized_at preenchido;
--   * limpar identificadores diretos (nome, contactos, NIF, licença, conta ligada);
--   * manter as relações (plantel, staff, presenças, cobranças) e os registos de auditoria;
--   * os filtros/índices de unicidade passam a considerar apenas linhas com anonymized_at IS NULL.

-- A tabela athlete já tinha anonymized_at desde a V1; a tabela team passa a ter o mesmo marcador.
ALTER TABLE team ADD COLUMN anonymized_at timestamptz;

-- Nomes de equipas anonimizadas deixam de bloquear a reutilização do nome.
DROP INDEX team_tenant_name_uk;
CREATE UNIQUE INDEX team_tenant_name_uk
    ON team (tenant_id, lower(name)) WHERE anonymized_at IS NULL;

-- Licenças e contas de atletas anonimizados também deixam de bloquear a reutilização.
DROP INDEX athlete_tenant_license_uk;
CREATE UNIQUE INDEX athlete_tenant_license_uk
    ON athlete (tenant_id, license_number)
    WHERE anonymized_at IS NULL AND license_number IS NOT NULL;

DROP INDEX athlete_tenant_user_uk;
CREATE UNIQUE INDEX athlete_tenant_user_uk
    ON athlete (tenant_id, user_id)
    WHERE anonymized_at IS NULL AND user_id IS NOT NULL;

-- O estado só interessa para registos vivos; as listagens ignoram os anonimizados.
DROP INDEX athlete_tenant_status_ix;
CREATE INDEX athlete_tenant_status_ix
    ON athlete (tenant_id, status) WHERE anonymized_at IS NULL;

-- Ao anonimizar, os dados pessoais obrigatórios passam a nulos. A restrição garante que
-- um atleta não anonimizado continua a ter data de nascimento e género.
ALTER TABLE athlete ALTER COLUMN birth_date DROP NOT NULL;
ALTER TABLE athlete ALTER COLUMN gender DROP NOT NULL;
ALTER TABLE athlete ADD CONSTRAINT athlete_live_data_chk
    CHECK (anonymized_at IS NOT NULL OR (birth_date IS NOT NULL AND gender IS NOT NULL));
