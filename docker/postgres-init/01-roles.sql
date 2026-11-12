-- Papéis da base de dados (apenas desenvolvimento e testes: passwords 'dev').
-- Em produção, criar estes papéis com passwords fortes, fora do repositório.

-- Dono do esquema: corre as migrações Flyway e operações de provisionamento privilegiadas.
-- Não é usado pela aplicação em runtime.
CREATE ROLE volei_migrator LOGIN PASSWORD 'dev' NOSUPERUSER BYPASSRLS;

-- Papel da aplicação: sem BYPASSRLS, sem ser dono das tabelas, sem DDL.
CREATE ROLE volei_app LOGIN PASSWORD 'dev'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO volei_migrator;
GRANT USAGE ON SCHEMA public TO volei_app;
