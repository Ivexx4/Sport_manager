-- O catálogo FPV é federal: não pertence a um clube nem a uma época.
-- A época guarda apenas a data de referência usada para calcular a idade.
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
