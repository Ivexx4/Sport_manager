# Clube Volei — fase 0 (fundações multitenant)

Java 25, Spring Boot 4.1.2, Maven, PostgreSQL 17 e Flyway.
Isolamento por clube com `tenant_id` + Row Level Security (RLS).

## Requisitos

- JDK 25 e Maven 3.9+
- Docker e Docker Compose (PostgreSQL local e testes Testcontainers)

## Arrancar a base de dados e a aplicação

```bash
docker compose up -d
mvn spring-boot:run
```

A base de dados cria os papéis `clube_owner` (migrações) e `clube_app` (runtime) através de `docker/initdb/01-roles.sql`.
A aplicação liga-se como `clube_app`; o Flyway liga-se como `clube_owner`.

## Segurança HTTP e JWT

A API é agora um OAuth2 Resource Server stateless:

- `/actuator/health`, `/actuator/health/**` e `/actuator/info` são públicos.
- Todos os restantes endpoints exigem `Authorization: Bearer <JWT>`.
- O JWT tem de ter assinatura HS256 válida, `iss` igual a `JWT_ISSUER` e claims `sub`, `iat` e `exp` obrigatórios, com datas de validade válidas (`nbf`, quando presente).
- Os endpoints `/api/**` exigem ainda o claim `tenant_id`, contendo um UUID.
- O `TenantFilter` só é executado depois da validação do Bearer token e ignora completamente `X-Tenant-Id`.
- O contexto do tenant é sempre limpo no fim do pedido.

### Configuração local

Por omissão existe uma chave de desenvolvimento para facilitar o arranque local. **Não a uses em ambientes partilhados ou produção.** Define um segredo aleatório de pelo menos 32 bytes, codificado em Base64, e o issuer esperado:

```bash
export JWT_SECRET_BASE64="$(openssl rand -base64 32)"
export JWT_ISSUER="clube-volei-local"
```

O token deve ser emitido por um serviço de identidade confiável, assinado com a chave configurada e conter, pelo menos, `iss`, `sub`, `iat`, `exp` e `tenant_id`. Esta etapa valida tokens; ainda não implementa login, gestão de utilizadores nem emissão de tokens.

Exemplo de chamada (substituir pelo token real emitido pelo serviço de identidade):

```bash
curl localhost:8080/api/athletes \
  -H 'Authorization: Bearer <JWT>'
```

Enviar `X-Tenant-Id` não altera o tenant escolhido. O tenant vem exclusivamente do claim validado no JWT.

## Testes

```bash
mvn clean verify
```

Os testes `TenantIsolationTest` provam, com PostgreSQL real, que a RLS isola os dados de clubes diferentes. `TenantFilterTest` verifica que o tenant vem do claim JWT e que o cabeçalho `X-Tenant-Id` não pode falsificá-lo.

## Como funciona o isolamento

1. Spring Security valida a assinatura e os claims do JWT.
2. `TenantFilter` lê `tenant_id` do `JwtAuthenticationToken` validado e coloca o UUID em `TenantContext`.
3. `TenantAwareJpaTransactionManager` executa `set_config('app.tenant_id', ?, true)` no início de cada transação (âmbito local).
4. As políticas RLS comparam `tenant_id` com `app.tenant_id`. Sem valor definido, não é possível ler nem escrever linhas protegidas.
5. O contexto é limpo no fim do pedido e o valor PostgreSQL termina com a transação.

## Regras para novas tabelas de negócio

- Coluna `tenant_id uuid NOT NULL REFERENCES tenant(id)` e `UNIQUE (tenant_id, id)`.
- `ENABLE` e `FORCE ROW LEVEL SECURITY`, política com `USING` e `WITH CHECK`.
- `GRANT` explícito a `clube_app` na própria migração.
- Chaves estrangeiras entre tabelas de negócio compostas: `(tenant_id, x_id)`.
- Teste de isolamento para cada tabela nova.

## Ainda por implementar na fase 0

- Emissão/renovação de tokens e integração com um fornecedor de identidade.
- Utilizadores, memberships e RBAC (administrador, direção, treinador, encarregado, etc.).
- `audit_log`, CI, perfis e gestão de segredos por ambiente.
- Backoffice mínimo para criar clubes.

## Nota de segurança

A chave HS256 por omissão é exclusivamente local e não deve ser usada em produção. Para produção, recomenda-se integrar um fornecedor OIDC e validar JWTs com chaves assimétricas/JWKS, em vez de partilhar um segredo HMAC entre serviços.
