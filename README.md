# Clube Volei: fase 0 (fundações multitenant)

Java 25 (LTS), Spring Boot 4.1.2, Maven, PostgreSQL 17, Flyway.
Isolamento por clube com `tenant_id` + Row Level Security.

## Requisitos

- JDK 25 e Maven 3.9+
- Docker (para o PostgreSQL local e para os testes com Testcontainers)

## Arrancar

```bash
docker compose up -d        # PostgreSQL com os papéis clube_owner (migrações) e clube_app (aplicação)
mvn spring-boot:run
```

Criar clubes de teste (o utilizador da plataforma é o dono; a aplicação só lê `tenant`):

```bash
docker compose exec postgres psql -U clube_owner -d clube -c \
  "INSERT INTO tenant (id, slug, name) VALUES
   ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','clube-a','Clube A'),
   ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','clube-b','Clube B');"

curl -X POST localhost:8080/api/athletes \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' \
  -d '{"fullName":"Ana Silva","birthDate":"2010-05-17"}'

curl localhost:8080/api/athletes -H 'X-Tenant-Id: aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'   # vê a Ana
curl localhost:8080/api/athletes -H 'X-Tenant-Id: bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'   # lista vazia
```

## Testes

```bash
mvn verify
```

`TenantIsolationTest` sobe um PostgreSQL real e prova que um tenant não lê, insere, altera nem apaga dados de outro,
que sem tenant não se vê nada, e que o tenant não vaza entre transações do pool.

## Como o isolamento funciona

1. `TenantFilter` guarda o tenant do pedido em `TenantContext` (provisório, ver abaixo).
2. `TenantAwareJpaTransactionManager` corre `set_config('app.tenant_id', ?, true)` no início de **cada** transação (âmbito local).
3. As políticas RLS comparam `tenant_id` com `app.tenant_id`. Sem valor definido, a comparação é NULL e não passa nenhuma linha.
4. A aplicação liga-se como `clube_app` (sem superuser nem BYPASSRLS); as migrações correm como o dono.

## Regras para novas tabelas de negócio

- Coluna `tenant_id uuid NOT NULL REFERENCES tenant(id)` e `UNIQUE (tenant_id, id)`.
- `ENABLE` e `FORCE ROW LEVEL SECURITY`, política com `USING` **e** `WITH CHECK`.
- `GRANT` explícito a `clube_app` na própria migração (não há privilégios por omissão).
- Chaves estrangeiras entre tabelas de negócio compostas: `(tenant_id, x_id)`.
- Teste de isolamento para a tabela nova.

## Pendente (próximos passos da fase 0)

- Autenticação (Spring Security + JWT) e resolução do tenant por subdomínio + claim JWT. O cabeçalho `X-Tenant-Id` é só provisório e **não é seguro**.
- Papéis (administrador, direção, treinador, encarregado...) e `audit_log`.
- CI (build + testes), perfis de configuração e segredos fora do repositório (as palavras-passe aqui são só de desenvolvimento).
- Backoffice mínimo para criar clubes.
