-- O catálogo FPV é federal: não pertence a um clube nem a uma época.
-- A época guarda apenas a data de referência usada para calcular a idade.

-- O nome antigo de cada escalão é a única informação que permite reaproveitar as referências
-- existentes (equipas, planos, inscrições e autorizações) depois de o catálogo ser substituído.
CREATE TEMP TABLE age_group_antigo AS SELECT id, name FROM age_group;

DROP TABLE age_group CASCADE;

CREATE TABLE age_group (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code        text NOT NULL UNIQUE,
    name        text NOT NULL UNIQUE,
    min_age     smallint NOT NULL CHECK (min_age >= 0),
    max_age     smallint CHECK (max_age IS NULL OR max_age >= min_age),
    is_active   boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO age_group (code, name, min_age, max_age) VALUES
    ('MINIS_A', 'Minis A', 6, 10),
    ('MINIS_B', 'Minis B', 10, 12),
    ('INFANTIS', 'Infantis', 13, 13),
    ('INICIADOS', 'Iniciados', 14, 14),
    ('CADETES', 'Cadetes', 15, 15),
    ('JUVENIS', 'Juvenis', 16, 16),
    ('JUNIORES', 'Juniores', 17, 17),
    ('SUB21', 'Sub21', 18, 20),
    ('SENIORES', 'Seniores', 19, NULL),
    ('VETERANOS_A', 'Veteranos A', 35, NULL),
    ('VETERANOS_B', 'Veteranos B', 45, NULL),
    ('VETERANOS_C', 'Veteranos C', 50, NULL);

-- Saneamento: as referências antigas apontam para ids que deixaram de existir. Remapeia-se pelo
-- nome (sem distinguir maiúsculas) e o que não tiver correspondência é eliminado ou limpo, para
-- que as chaves estrangeiras abaixo possam ser criadas numa base já povoada.

UPDATE athlete_season_registration r SET age_group_id = g.id
FROM age_group_antigo o JOIN age_group g ON lower(g.name) = lower(o.name)
WHERE r.age_group_id = o.id;

DELETE FROM athlete_season_registration r
WHERE NOT EXISTS (SELECT 1 FROM age_group g WHERE g.id = r.age_group_id);

-- A autorização é única por (clube, época, atleta, escalão); vários escalões antigos podem
-- colapsar no mesmo escalão novo, pelo que só a primeira linha de cada chave é remapeada.
WITH mapeado AS (
    SELECT p.id AS promotion_id, g.id AS novo_id,
           row_number() OVER (
               PARTITION BY p.tenant_id, p.season_id, p.athlete_id, g.id
               ORDER BY p.created_at, p.id) AS ordem
    FROM season_promotion p
    JOIN age_group_antigo o ON o.id = p.age_group_id
    JOIN age_group g ON lower(g.name) = lower(o.name)
)
UPDATE season_promotion p SET age_group_id = m.novo_id
FROM mapeado m WHERE p.id = m.promotion_id AND m.ordem = 1;

DELETE FROM season_promotion p
WHERE NOT EXISTS (SELECT 1 FROM age_group g WHERE g.id = p.age_group_id);

UPDATE team t SET age_group_id = g.id
FROM age_group_antigo o JOIN age_group g ON lower(g.name) = lower(o.name)
WHERE t.age_group_id = o.id;

UPDATE team SET age_group_id = NULL
WHERE age_group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM age_group g WHERE g.id = team.age_group_id);

UPDATE fee_plan f SET age_group_id = g.id
FROM age_group_antigo o JOIN age_group g ON lower(g.name) = lower(o.name)
WHERE f.age_group_id = o.id;

UPDATE fee_plan SET age_group_id = NULL
WHERE age_group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM age_group g WHERE g.id = fee_plan.age_group_id);

ALTER TABLE team
    ADD CONSTRAINT team_age_group_fk
    FOREIGN KEY (age_group_id) REFERENCES age_group (id) ON DELETE SET NULL;

ALTER TABLE fee_plan
    ADD CONSTRAINT fee_plan_age_group_fk
    FOREIGN KEY (age_group_id) REFERENCES age_group (id) ON DELETE SET NULL;

ALTER TABLE athlete_season_registration
    ADD CONSTRAINT athlete_season_registration_age_group_fk
    FOREIGN KEY (age_group_id) REFERENCES age_group (id) ON DELETE CASCADE;

ALTER TABLE season_promotion
    ADD CONSTRAINT season_promotion_age_group_fk
    FOREIGN KEY (age_group_id) REFERENCES age_group (id) ON DELETE CASCADE;

GRANT SELECT ON age_group TO volei_app;
