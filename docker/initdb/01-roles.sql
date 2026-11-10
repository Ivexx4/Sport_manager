-- Papel usado pela aplicação em runtime.
-- NOSUPERUSER e NOBYPASSRLS são essenciais: sem isto a RLS não se aplica.
-- Não é dono de nenhuma tabela (FORCE RLS cobre também os donos, mas mantemos a separação).
CREATE ROLE clube_app LOGIN PASSWORD 'clube_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
