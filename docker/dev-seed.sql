-- Dados de desenvolvimento: um clube e um administrador. NÃO usar em produção.
-- Correr depois de a aplicação ter arrancado uma vez (o Flyway cria as tabelas):
--   docker compose exec -T db psql -U postgres -d volei < docker/dev-seed.sql
-- O superuser não está sujeito a RLS, por isso pode inserir em qualquer tenant.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO tenant (id, name, slug)
VALUES ('00000000-0000-0000-0000-00000000000a', 'Clube Piloto', 'clube-piloto')
ON CONFLICT DO NOTHING;

-- Password: admin123 (bcrypt do pgcrypto, compatível com o BCryptPasswordEncoder do Spring)
INSERT INTO app_user (id, email, name, password_hash)
VALUES ('00000000-0000-0000-0000-0000000000a1', 'admin@clube-piloto.local', 'Administrador',
        '{bcrypt}' || crypt('admin123', gen_salt('bf', 10)))
ON CONFLICT DO NOTHING;

INSERT INTO membership (tenant_id, user_id, role)
VALUES ('00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a1', 'ADMIN')
ON CONFLICT DO NOTHING;
