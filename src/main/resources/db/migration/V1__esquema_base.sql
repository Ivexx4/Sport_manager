-- Tabelas da plataforma (sem tenant_id): tenant e app_user.

CREATE TABLE tenant (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL,
    slug        text        NOT NULL UNIQUE
                CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    nif         text,
    status      text        NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    plan        text        NOT NULL DEFAULT 'PILOT',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    name          text        NOT NULL,
    password_hash text,
    status        text        NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX app_user_email_uk ON app_user (lower(email));

-- Tabelas de negócio: todas com tenant_id, created_at, updated_at, created_by.

CREATE TABLE membership (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    user_id     uuid        NOT NULL REFERENCES app_user (id),
    role        text        NOT NULL
                CHECK (role IN ('ADMIN', 'DIRECAO', 'FINANCEIRO',
                                'TREINADOR', 'ATLETA', 'ENCARREGADO')),
    status      text        NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    -- alvo para chaves estrangeiras compostas (tenant_id, id) noutras tabelas
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, user_id, role)
);

CREATE TABLE athlete (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    full_name       text        NOT NULL,
    birth_date      date        NOT NULL,
    gender          text        NOT NULL CHECK (gender IN ('F', 'M')),
    nif             text,
    email           text,
    phone           text,
    license_number  text,
    anonymized_at   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    UNIQUE (tenant_id, id)
);

CREATE INDEX athlete_tenant_name_ix ON athlete (tenant_id, full_name);
CREATE UNIQUE INDEX athlete_tenant_license_uk
    ON athlete (tenant_id, license_number) WHERE license_number IS NOT NULL;
CREATE INDEX membership_tenant_user_ix ON membership (tenant_id, user_id);

-- Registo de auditoria: só de escrita (ver permissões na V2).
CREATE TABLE audit_log (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    user_id     uuid,
    action      text        NOT NULL,
    entity      text        NOT NULL,
    entity_id   uuid,
    before_data jsonb,
    after_data  jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_tenant_created_ix ON audit_log (tenant_id, created_at DESC);
