#!/bin/sh
# Papéis da base de dados em staging, com passwords vindas do ambiente.
# Corre apenas na primeira inicialização do volume do PostgreSQL.
#
# Em desenvolvimento/testes usa-se docker/postgres-init/01-roles.sql (passwords "dev").
# Este ficheiro existe à parte para nunca haver passwords fixas em staging.

set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v migrator_password="$VOLEI_MIGRATOR_PASSWORD" \
    -v app_password="$VOLEI_APP_PASSWORD" <<'EOSQL'
-- Dono do esquema: corre as migrações Flyway e operações privilegiadas.
-- Não é usado pela aplicação em runtime.
CREATE ROLE volei_migrator LOGIN PASSWORD :'migrator_password' NOSUPERUSER BYPASSRLS;

-- Papel da aplicação: sem BYPASSRLS, sem ser dono das tabelas, sem DDL.
CREATE ROLE volei_app LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO volei_migrator;
GRANT USAGE ON SCHEMA public TO volei_app;
EOSQL
