# Gestão de Clubes de Voleibol

Monólito modular (Java 25, Spring Boot 4.1.2, Maven) com PostgreSQL e multitenancy por `tenant_id` + Row Level Security.
Estado atual: **fase 0 (fundações) concluída, fase 1 em curso** com o módulo de **atletas** (CRUD completo):
tenancy com RLS, papéis da base de dados, filtro de tenant, testes de isolamento, autenticação por JWT próprio,
limitação de tentativas de login, convites/utilizadores, recuperação de password, provisionamento de clubes,
e-mail por outbox (com tentativas), limpeza periódica de dados expirados, CI e CD para staging; atletas,
encarregados de educação e uma base mínima de equipas com autorização por recurso.

## Requisitos

- JDK 25 (o `pom.xml` fixa `java.version` a 25)
- Maven 3.6.3+
- Docker (PostgreSQL 16 local, Mailpit, Testcontainers e imagem de staging)

## Arranque

```bash
docker compose up -d                              # PostgreSQL 16 (volei_migrator/volei_app) + Mailpit (SMTP/UI)
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run    # perfil dev: segredo JWT, SMTP local e bootstrap key
```

Há duas formas de ter um clube em desenvolvimento (escolhe uma):

**A. Dados de exemplo (seed)** — `clube-piloto`, com `admin@clube-piloto.local` / `admin123`:

```bash
docker compose exec -T db psql -U postgres -d volei < docker/dev-seed.sql
```

**B. Provisionamento pela API** — cria um clube novo e o primeiro `ADMIN` (ver secção seguinte).

- **E-mail em dev:** o Mailpit recebe os e-mails em `localhost:1025`; interface web em [http://localhost:8025](http://localhost:8025).
- **Se a base de dados local não arrancar** com `database files are incompatible with server`, o volume `pgdata` foi criado por outra versão principal do PostgreSQL: usa `docker compose down -v` (apaga os dados locais) ou alinha a imagem `postgres` no compose com a versão do volume.

Fora do perfil `dev`, a variável `JWT_SECRET` é obrigatória (Base64, 32 bytes ou mais): `openssl rand -base64 48`.
O provisionamento de novos clubes requer `APP_PLATFORM_BOOTSTRAP_KEY` (no perfil `dev` existe uma chave por omissão: `dev-bootstrap-key`).

### Provisionamento de um clube

```bash
curl -s -X POST http://localhost:8080/api/v1/platform/tenants \
  -H 'Content-Type: application/json' \
  -H 'X-Bootstrap-Key: dev-bootstrap-key' \
  -d '{"name":"Clube Piloto 2","slug":"clube-piloto-2","adminEmail":"admin@clube-piloto-2.local","adminName":"Admin","adminPassword":"admin12345!"}'
```

Cria o clube e o primeiro administrador (`ADMIN`) com a password indicada (se o e-mail já existir, a conta é associada ao novo clube e a password existente mantém-se). Respostas: `201` com `tenantId`/`adminUserId`; `400` para validação; `403` para chave inválida; `409` se o slug já existir; `503` se o provisionamento estiver desativado (chave vazia).

O tenant resolve-se pelo subdomínio (`*.localhost` funciona na maioria dos sistemas). Com o seed acima:

```bash
curl http://clube-piloto.localhost:8080/api/v1/ping

curl -s -X POST http://clube-piloto.localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@clube-piloto.local","password":"admin123"}'

curl http://clube-piloto.localhost:8080/api/v1/auth/me -H "Authorization: Bearer <accessToken>"
```

Com o provisionamento (B), usa o slug, o e-mail e a password indicados no pedido.

Um subdomínio desconhecido devolve 404; um pedido de autenticação sem subdomínio devolve 400.

## Autenticação

| Endpoint                    | Descrição                                                                                                               |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `POST /api/v1/auth/login`   | `{email, password}` → `{accessToken, refreshToken, tokenType, expiresIn}`; `401` em falha, `429` após demasiadas falhas |
| `POST /api/v1/auth/refresh` | `{refreshToken}` → novo par de tokens (rotação: cada refresh token só serve uma vez)                                    |
| `POST /api/v1/auth/logout`  | `{refreshToken}` → 204; revoga o refresh token                                                                          |
| `GET /api/v1/auth/me`       | Utilizador, clube e papéis do token                                                                                     |

- **Access token:** JWT HS256 de 15 minutos, com `sub` (utilizador), `tenant_id` e `roles`. É validado pelo
  Spring Security como resource server, e o `tenant_id` tem de coincidir com o clube do subdomínio:
  um token do clube A é recusado no clube B.
- **Papéis:** vêm de `membership` (um utilizador pode ter papéis diferentes em cada clube) e ficam como
  autoridades `ROLE_ADMIN`, `ROLE_TREINADOR`, etc., prontas para `@PreAuthorize`.
- **Refresh token:** opaco, guardado só como hash SHA-256 (tabela `refresh_token`, com RLS), 30 dias.
  Reutilizar um refresh token já usado revoga todas as sessões desse utilizador no clube.
- **Falhas de login:** email desconhecido, password errada, membership desativada ou utilizador sem papel
  no clube dão a mesma resposta (401). Todas as tentativas ficam em `audit_log`.
- **Limitação de tentativas (força bruta):** por **clube + IP**, só contam as falhas. Por omissão, 5 falhas
  em 5 minutos bloqueiam esse IP nesse clube durante 15 minutos; o pedido seguinte recebe `429` com
  `Retry-After` (segundos), sem consultar a base de dados. Um login com sucesso limpa o contador. Configurável
  em `app.security.login-rate-limit` (`APP_LOGIN_RATE_LIMIT_ENABLED`, `_MAX_ATTEMPTS`, `_WINDOW`,
  `_BLOCK_DURATION`); o estado é em memória, por instância (com várias instâncias, usar um armazenamento
  partilhado como Redis para um limite global). Atrás de um proxy que escreve `X-Forwarded-For`, ativar
  `trust-forwarded-headers` (`APP_LOGIN_RATE_LIMIT_TRUST_FORWARDED_HEADERS=true`); nunca ativar sem esse proxy.
- **Passwords:** hash `{bcrypt}` via `DelegatingPasswordEncoder`.

## Provisionamento e gestão de contas

| Endpoint                                     | Quem                    | Descrição                                                                            |
|----------------------------------------------|-------------------------|--------------------------------------------------------------------------------------|
| `POST /api/v1/platform/tenants`              | chave `X-Bootstrap-Key` | `{name, slug, adminEmail, adminName, adminPassword}` → 201; cria clube e 1.º `ADMIN` |
| `POST /api/v1/users/invitations`             | `ADMIN`, `DIRECAO`      | `{email, name, roles[]}` → 202; enfileira o convite por e-mail                       |
| `GET /api/v1/users`                          | `ADMIN`, `DIRECAO`      | 200; lista `{userId, email, name, status, roles:[{role, status}]}` do clube          |
| `POST /api/v1/users/{userId}/disable`        | `ADMIN`, `DIRECAO`      | 204; desativa todos os papéis do membro no clube e revoga as sessões                 |
| `DELETE /api/v1/users/{userId}/roles/{role}` | `ADMIN`, `DIRECAO`      | 204; remove um papel do membro no clube                                              |
| `POST /api/v1/auth/invitations/accept`       | público                 | `{token, name?, password?}` → 204; cria a conta e os papéis no clube                 |
| `POST /api/v1/auth/password/forgot`          | público                 | `{email}` → 202, exista ou não a conta                                               |
| `POST /api/v1/auth/password/reset`           | público                 | `{token, newPassword}` → 204                                                         |
| `POST /api/v1/auth/password/change`          | autenticado             | `{currentPassword, newPassword}` → 204                                               |

`DIRECAO` não pode atribuir/retirar `ADMIN` ou `DIRECAO` (só `ADMIN`), nem desativar membros que tenham papéis de gestão ou alterar papéis de um `ADMIN`. Ninguém pode desativar a própria conta nem retirar o próprio papel. O clube tem de manter pelo menos um `ADMIN` ativo (409). Desativar um membro ou remover o último papel ativo revoga os refresh tokens desse utilizador no clube; os access tokens já emitidos continuam válidos até expirarem (15 min).

- **Convites:** válidos 7 dias, uso único, um pendente por email (um novo substitui o anterior). `DIRECAO` só atribui
  papéis que não sejam `ADMIN` nem `DIRECAO`. Quem ainda não tem conta define a password ao aceitar; quem já tem conta
  (noutro clube) só precisa do token, e a password mantém-se. O mesmo email é uma só conta em todos os clubes.
- **Recuperação:** link válido 1 hora, uso único, máximo 3 pedidos por hora e utilizador. A resposta é igual exista ou
  não a conta, e só quem tem papel ativo no clube recebe o e-mail.
- **Depois de mudar ou repor a password:** todos os refresh tokens anteriores deixam de servir, também nos outros clubes
  (o login verifica `password_changed_at`). Os access tokens em circulação expiram em 15 minutos.
- **Política de passwords:** mínimo 10 caracteres e máximo 72 bytes (limite do bcrypt), sem regras de composição.
- **Tokens:** opacos, 256 bits, guardados só como hash SHA-256. Os links dos e-mails seguem
  `app.links.web-url-template` (por omissão `http://{slug}.localhost:5173`); o frontend tem de ter as rotas
  `/accept-invitation` e `/reset-password`, que recebem `?token=...`.
- **E-mail (outbox + tentativas):** os e-mails são enfileirados na tabela `email_outbox` (transacional com a operação) e enviados de forma assíncrona, com até 5 tentativas e backoff exponencial; o processador corre a cada 30 s por omissão (`app.mail.outbox.poll-interval`, `APP_MAIL_OUTBOX_POLL_INTERVAL`) e os estados são `PENDING`/`SENT`/`FAILED`. Se `spring.mail.host` estiver definido, o transporte é SMTP (configurar credenciais e `app.mail.from`/`APP_MAIL_FROM`); caso contrário, o transporte regista em log. No perfil `dev` o `docker compose` inclui **Mailpit** (SMTP `localhost:1025`, UI [http://localhost:8025](http://localhost:8025)) já configurado. Em produção, configurar SMTP (ou substituir o transporte) e monitorizar mensagens em `FAILED`.

## Atletas e equipas (fase 1)

Primeiro módulo da fase 1: **atletas** com CRUD completo, encarregados de educação e ligação opcional a uma
conta de utilizador. Para suportar o âmbito do `TREINADOR`, entrou também uma **base mínima de equipas**
(equipa, staff e plantel); épocas, escalões e plantéis por época ficam para o módulo `teams` completo.

### Endpoints de atletas

| Endpoint                                        | Quem pode                                                                 | Descrição                                                                 |
|-------------------------------------------------|---------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `GET /api/v1/athletes`                          | `ADMIN`, `DIRECAO`, `FINANCEIRO` (todos) e `TREINADOR`/`ATLETA`/`ENCARREGADO` (âmbito) | Lista paginada: `q` (nome), `status`, `teamId`, `page` (≥0), `size` (1–100, omissão 20) |
| `GET /api/v1/athletes/{id}`                     | idem                                                                      | Detalhe; fora do âmbito devolve `404`                                     |
| `POST /api/v1/athletes`                         | `ADMIN`, `DIRECAO`                                                        | Cria; `409` para licença repetida ou conta já ligada                      |
| `PUT /api/v1/athletes/{id}`                     | `ADMIN`, `DIRECAO`, e o âmbito de `TREINADOR`/`ATLETA`/`ENCARREGADO`      | Atualiza os dados; fora do âmbito de leitura `404`, dentro mas sem escrita `403` |
| `DELETE /api/v1/athletes/{id}`                  | `ADMIN`, `DIRECAO`                                                        | Desativa e anonimiza (remoção não destrutiva), mantendo histórico e relações |
| `POST /api/v1/athletes/{id}/account`            | `ADMIN`, `DIRECAO`                                                        | `{userId}` liga a conta; a conta tem de ter membership ativo no clube     |
| `DELETE /api/v1/athletes/{id}/account`          | `ADMIN`, `DIRECAO`                                                        | Desliga a conta                                                           |
| `PATCH /api/v1/athletes/{id}/status`            | `ADMIN`, `DIRECAO`                                                        | `{status}`: `ACTIVE` ou `INACTIVE`                                        |
| `GET /api/v1/athletes/{id}/guardians`           | mesma visibilidade do atleta                                              | Lista os encarregados de educação                                         |
| `POST /api/v1/athletes/{id}/guardians`          | `ADMIN`, `DIRECAO`                                                        | `{userId, relationship?}`; `409` se já estiver associado                  |
| `DELETE /api/v1/athletes/{id}/guardians/{userId}` | `ADMIN`, `DIRECAO`                                                      | Remove a associação                                                       |

`status` é `ACTIVE`/`INACTIVE`; `gender` é `F`/`M`; a data de nascimento tem de ser passada; `nif` são 9 dígitos
(opcional); o `email` é normalizado para minúsculas; a licença é única por clube, sem distinguir maiúsculas.
Cada conta (`athlete.user_id`) só pode estar ligada a um atleta no mesmo clube.

### Endpoints de equipas

| Endpoint                                      | Quem pode                          | Descrição                                                        |
|-----------------------------------------------|------------------------------------|------------------------------------------------------------------|
| `GET /api/v1/teams`                           | `ADMIN`, `DIRECAO`, `TREINADOR`    | Todas para a gestão; o treinador só vê as equipas em que é staff |
| `GET /api/v1/teams/{id}`                      | idem                               | Detalhe; fora do âmbito devolve `404`                            |
| `POST /api/v1/teams`                          | `ADMIN`, `DIRECAO`                 | `{name, status?}`; nome único por clube (sem distinguir maiúsculas) |
| `PUT /api/v1/teams/{id}`                      | `ADMIN`, `DIRECAO`                 | Atualiza nome/estado                                             |
| `DELETE /api/v1/teams/{id}`                   | `ADMIN`, `DIRECAO`                 | Desativa e anonimiza; staff e plantel ficam preservados para histórico |
| `GET /api/v1/teams/{id}/staff`                | mesma visibilidade da equipa       | Lista treinadores/adjuntos                                       |
| `POST /api/v1/teams/{id}/staff`               | `ADMIN`, `DIRECAO`                 | `{userId, role?}`: `TREINADOR` ou `TREINADOR_ADJUNTO`            |
| `DELETE /api/v1/teams/{id}/staff/{userId}`    | `ADMIN`, `DIRECAO`                 | Remove o staff                                                   |
| `GET /api/v1/teams/{id}/athletes`             | mesma visibilidade da equipa       | Lista o plantel                                                  |
| `POST /api/v1/teams/{id}/athletes`            | `ADMIN`, `DIRECAO`                 | `{athleteId}`; o atleta tem de ser do mesmo clube                |
| `DELETE /api/v1/teams/{id}/athletes/{athleteId}` | `ADMIN`, `DIRECAO`               | Remove do plantel                                                |

A gestão de staff e plantel é **exclusiva de `ADMIN`/`DIRECAO`**: um treinador não pode adicionar atletas à
própria equipa para alargar o seu âmbito de autorização.

### Matriz de autorização por recurso

| Papel         | Lê                                                        | Escreve                                                                                     |
|---------------|-----------------------------------------------------------|---------------------------------------------------------------------------------------------|
| `ADMIN`       | tudo no clube                                             | tudo no clube                                                                               |
| `DIRECAO`     | tudo no clube                                             | tudo no clube                                                                               |
| `TREINADOR`   | atletas e equipas em que é staff                          | atualiza atletas dessas equipas; não cria/apaga atletas, não muda estado, não gere contas/encarregados/plantel/staff |
| `ATLETA`      | o próprio registo                                         | atualiza o próprio registo; não cria/apaga, não muda estado, não gere contas/encarregados   |
| `ENCARREGADO` | os seus educandos                                         | atualiza os seus educandos; não cria/apaga, não muda estado, não gere contas/encarregados   |
| `FINANCEIRO`  | atletas                                                   | —                                                                                           |

Regras transversais:

- A visibilidade é a **união** dos papéis ativos: um utilizador `ATLETA` + `ENCARREGADO` vê o próprio registo
  e os educandos. O papel do token tem de corresponder a uma membership ativa no clube.
- Um recurso fora do âmbito devolve `404` (não revela a existência); um recurso visível mas sem permissão de
  escrita devolve `403`.
- O RLS por `tenant_id` continua a ser a barreira final: nenhuma query vê dados de outro clube.
- Encarregados e contas ligadas têm de ter membership ativo no clube; a conta de atleta é única por clube.
- Todas as operações de escrita ficam em `audit_log` (`ATHLETE_*` e `TEAM_*`).

### Remoção não destrutiva (convenção de desenvolvimento)

O `DELETE` de atletas e equipas **não apaga a linha**. Aplica a convenção RGPD da plataforma:

- `status = 'INACTIVE'` e `anonymized_at = now()`;
- limpeza dos identificadores diretos (nome, contactos, NIF, licença, conta ligada, data de nascimento e género);
- as relações (`team_athlete`, `team_staff`, presenças, cobranças) e a auditoria mantêm-se, para preservar o
  histórico e não quebrar chaves externas;
- os registos anonimizados deixam de aparecer nas leituras/listagens e não bloqueiam a reutilização de licenças,
  contas ou nomes;
- os encarregados de educação (dados pessoais) são removidos quando o atleta é anonimizado.

Esta é a convenção para **todos os módulos**: uma entidade com histórico não é eliminada fisicamente. Novas tabelas
devem ter `anonymized_at` (e `status` quando aplicável) e os índices de unicidade devem ser parciais
(`WHERE anonymized_at IS NULL`). Só dados sem valor histórico — tokens, convites e mensagens da outbox — são
apagados pela limpeza periódica.

## Manutenção

A limpeza periódica remove, em todos os clubes, dados que já não podem ser usados:

| Dados                  | Quando são removidos                                                            |
|------------------------|---------------------------------------------------------------------------------|
| `invitation`           | expirados, aceites ou revogados há mais de `invitation-retention`               |
| `password_reset_token` | usados ou expirados há mais de `password-reset-retention`                       |
| `refresh_token`        | expirados ou revogados há mais de `refresh-token-retention`                     |
| `email_outbox`         | `SENT`/`FAILED` com mais de `outbox-retention` (os `PENDING` nunca são tocados) |

Corre a cada `app.maintenance.cleanup.interval` (por omissão 6 h, com a primeira execução 1 min após o
arranque) e pode ser desativada com `APP_MAINTENANCE_CLEANUP_ENABLED=false`. As retenções configuram-se por
`APP_MAINTENANCE_CLEANUP_INVITATION_RETENTION`, `..._PASSWORD_RESET_RETENTION`, `..._REFRESH_TOKEN_RETENTION`
e `..._OUTBOX_RETENTION` (ISO-8601; por omissão 30 d, 7 d, 30 d e 30 d). A função SQL `purge_expired_data`
é `SECURITY DEFINER` para apagar em todos os clubes de uma só vez e limita os limites a "agora", pelo que
nunca remove um token ou convite ainda válido.

## Testes

```bash
mvn test
```

Os testes de integração precisam de Docker. Corre-se um PostgreSQL real com Testcontainers:

- `TenantIsolationTest`: usa o papel `volei_app` por JDBC e prova que um tenant não lê, não insere,
  não altera e não apaga dados de outro; que o valor do tenant não sobrevive à transação; que o papel
  não tem `BYPASSRLS` nem é dono das tabelas; que toda a tabela com `tenant_id` tem RLS forçada; e que
  `app_user` (sem `tenant_id`) tem RLS forçada e só é visível/alterável no contexto do tenant com relação.
- `TenantTransactionManagerTest`: prova que o gestor de transações do Spring passa o tenant à base de dados
  e que não há fuga entre pedidos com um pool de uma só ligação.
- `AuthFlowTest`: fluxo completo por MockMvc, com o filtro de tenant e a cadeia de segurança reais.
  Cobre token de outro clube, token adulterado, utilizador sem papel no clube, membership desativada,
  rotação e reutilização de refresh tokens, logout e auditoria.
- `AccountFlowTest`: convites (papéis permitidos, uso único, expiração, clube errado, conta já existente noutro clube),
  recuperação (limite por hora, não revela contas, invalida sessões) e alteração de password. Os e-mails são
  capturados em memória e os tokens saem dos links, como na vida real.
- `LoginRateLimitFlowTest`: bloqueio por clube + IP ao fim de N falhas (`429` + `Retry-After`), independência
  entre clubes e entre IPs, reposição do contador por um login com sucesso e `X-Forwarded-For` ignorado por omissão.
- `ProvisioningFlowTest`: `POST /api/v1/platform/tenants` cria o clube e o primeiro `ADMIN` que entra logo;
  uma conta já existente é associada sem trocar a password; chave em falta/errada (`403`), slug repetido (`409`)
  e pedido inválido (`400`) não deixam clube criado.
- `MemberManagementFlowTest`: `GET /api/v1/users` lista só os membros do clube; desativação revoga sessões;
  regras de `ADMIN`/`DIRECAO` (gestão de gestores, própria conta); remoção de papéis; alvos de outro clube
  dão `404`; e o último `ADMIN` ativo não pode ser desativado nem perder o papel (`409`).
- `AthleteFlowTest`: fluxo de atletas contra PostgreSQL real. Cobre CRUD e validação, `409` de licença/conta
  repetida, paginação e filtros (`q`, `status`, `teamId`), isolamento entre clubes, encarregados de educação e
  auditoria. Prova a autorização por recurso: `TREINADOR` só nas suas equipas (perde o acesso ao sair do plantel),
  `ATLETA` só no próprio registo, `ENCARREGADO` só nos educandos, `FINANCEIRO` só lê e `ADMIN`/`DIRECAO` gerem tudo.
  Cobre também a remoção não destrutiva: anonimização, preservação do plantel, remoção dos encarregados e
  reutilização da licença e da conta.
- `TeamFlowTest`: CRUD de equipas, nome único, autorização da listagem, staff e plantel, isolamento entre clubes,
  remoção não destrutiva (a equipa é anonimizada e staff/plantel ficam preservados) e o âmbito do `TREINADOR`
  (só vê as equipas em que é staff e não as gere).
- `ExpiredDataPurgerTest`: a limpeza remove convites/tokens expirados, aceites, usados ou revogados de
  **vários clubes** numa só chamada, e mantém os dados recentes/válidos e as mensagens `PENDING` da outbox.

Os testes unitários (`LoginRateLimiterTest`, `ClientIpResolverTest`, `ProvisioningServiceTest`,
`ExpiredDataPurgerUnitTest`) não precisam de Docker; os dois primeiros controlam o relógio para verificar a
expiração do bloqueio sem esperar.

Integração contínua: workflow GitHub Actions em `.github/workflows/ci.yml` (JDK 25, `mvn -B -ntp test`,
com Docker disponível no runner). Em `main`, o mesmo workflow publica a imagem e faz deploy para staging
(ver a secção seguinte).

## CI/CD e staging

O workflow `.github/workflows/ci.yml` tem três jobs:

| Job             | Quando corre                                    | O que faz                                                                                                                     |
|-----------------|-------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `test`          | todos os pushes, pull requests e execução manual | `mvn -B -ntp test` com JDK 25 (o Testcontainers usa o Docker do runner)                                                        |
| `image`         | depois dos testes                               | valida o `Dockerfile` em pull requests; em `main` publica `ghcr.io/<owner>/<repo>:<sha>` e `:staging` no GHCR                   |
| `deploy-staging`| só em `main`                                    | por SSH, atualiza a stack Docker Compose no servidor e espera pelos healthchecks; falha com os logs se a app não ficar saudável |

A imagem é multi-stage (`Dockerfile`): build com Maven/JDK 25 e runtime `eclipse-temurin:25-jre-alpine`, com
utilizador sem privilégios e healthcheck em `/api/v1/ping`. A tag `:<sha>` é imutável e é a usada no deploy;
`:staging` aponta sempre para a última versão de `main`.

### Configurar o staging no GitHub

Criar o environment **staging** em *Settings > Environments* (pode ter revisores obrigatórios) e definir:

**Secrets**

| Nome                              | Descrição                                                                 |
|-----------------------------------|---------------------------------------------------------------------------|
| `STAGING_HOST`                    | Host SSH do servidor                                                      |
| `STAGING_USER`                    | Utilizador SSH com acesso ao Docker                                       |
| `STAGING_SSH_KEY`                 | Chave privada (OpenSSH); a pública fica em `~/.ssh/authorized_keys`       |
| `STAGING_KNOWN_HOSTS`             | Saída de `ssh-keyscan -H <host>`; evita desativar a verificação do servidor |
| `STAGING_DB_SUPERUSER_PASSWORD`   | Password do `postgres` (só para o arranque do contentor)                  |
| `STAGING_DB_APP_PASSWORD`         | Password do `volei_app` (aplicação, sujeita a RLS)                         |
| `STAGING_DB_MIGRATOR_PASSWORD`    | Password do `volei_migrator` (Flyway; `BYPASSRLS`, nunca usada em runtime) |
| `STAGING_JWT_SECRET`              | Base64 com 32+ bytes: `openssl rand -base64 48`                           |
| `STAGING_PLATFORM_BOOTSTRAP_KEY`  | Chave de `POST /api/v1/platform/tenants`                                  |
| `STAGING_MAIL_USERNAME`           | Opcional; só com SMTP real                                                |
| `STAGING_MAIL_PASSWORD`           | Opcional; só com SMTP real                                                |

**Variables**

| Nome                                | Omissão              | Descrição                                                               |
|-------------------------------------|----------------------|-------------------------------------------------------------------------|
| `STAGING_URL`                       | —                    | URL mostrado no environment (ex.: `https://staging.example.com`)        |
| `STAGING_BASE_DOMAIN`               | —                    | Domínio base dos clubes (ex.: `staging.example.com`)                    |
| `STAGING_WEB_URL_TEMPLATE`          | —                    | Links dos e-mails (ex.: `https://{slug}.staging.example.com`)           |
| `STAGING_PORT`                      | `22`                 | Porto SSH                                                               |
| `STAGING_DIR`                       | `gestao-staging`     | Diretório no servidor (relativo à home ou absoluto, sem `~`)            |
| `STAGING_APP_PORT`                  | `8080`               | Porto no loopback do servidor para o reverse proxy                      |
| `STAGING_DB_NAME`                   | `volei`              | Nome da base de dados                                                   |
| `STAGING_MAIL_HOST`                 | vazio                | SMTP; vazio usa o transporte de log (a outbox continua a funcionar)     |
| `STAGING_MAIL_PORT`                 | `587`                | Porto SMTP                                                              |
| `STAGING_MAIL_FROM`                 | `no-reply@<base-domain>` | Remetente                                                          |
| `STAGING_MAIL_SMTP_AUTH`            | `true`               | Autenticação SMTP                                                       |
| `STAGING_MAIL_SMTP_STARTTLS`        | `true`               | STARTTLS no SMTP (desativar só para um relay local sem TLS)             |
| `STAGING_TRUST_FORWARDED_HEADERS`   | `true`               | Usar `X-Forwarded-For` no limite de login (só atrás do proxy)           |

### Servidor de staging

- Docker Engine com Compose v2 e um utilizador no grupo `docker`.
- DNS wildcard `*.staging.example.com` a apontar para o servidor.
- Reverse proxy (Caddy, nginx, Traefik, ...) com TLS wildcard a encaminhar
  `*.<STAGING_BASE_DOMAIN>` para `127.0.0.1:<STAGING_APP_PORT>`.
- O proxy tem de **preservar o `Host`** e escrever `X-Forwarded-Host` (o subdomínio do clube),
  `X-Forwarded-Proto` e `X-Forwarded-For`; o perfil `staging` usa `server.forward-headers-strategy=framework`.
  Nunca aceitar estes cabeçalhos vindos diretamente do cliente.
- Expor só 22, 80 e 443; a aplicação e o PostgreSQL não publicam portas públicas (a app fica em `127.0.0.1`).

No primeiro deploy, `docker-compose.staging.yml` cria o volume `pgdata` e os papéis
`volei_migrator`/`volei_app` com as passwords dos secrets, através de
`docker/postgres-init-staging/01-roles.sh`. Se o volume já existir, esse script **não volta a correr**:
alterar as passwords exige rodá-las manualmente na base de dados. As migrações Flyway correm no arranque da
aplicação, com o papel `volei_migrator`.

Para criar o primeiro clube, usar o endpoint de provisionamento com `X-Bootstrap-Key` (ver acima).
Para acompanhar o serviço:

```bash
ssh <STAGING_USER>@<STAGING_HOST>
cd gestao-staging                       # ou o valor de STAGING_DIR
docker compose --env-file .env -f docker-compose.staging.yml ps
docker compose --env-file .env -f docker-compose.staging.yml logs -f app
```

### Deploy e rollback

O deploy corre automaticamente em cada push para `main`; também pode ser disparado à mão em
*Actions > CI > Run workflow* (no branch `main`). Para voltar a uma versão anterior, usar a tag imutável
`:<sha>` do commit pretendido (re-executar o workflow desse commit ou, no servidor, alterar `APP_IMAGE` no
`.env` e correr `docker compose ... up -d --wait`). A tag `:staging` não serve para rollback porque se move.

## Estrutura

| Pacote                                                | Conteúdo                                                                                                                                     |
|-------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `tenancy`                                             | `TenantContext`, `TenantFilter` (subdomínio), `TenantAwareJpaTransactionManager` (`set_config` por transação)                                |
| `identity`                                            | Segurança (JWT, papéis, limitação de login), autenticação (`Auth*`), contas/passwords (`Account*`) e provisionamento de clubes (`Platform*`) |
| `notifications`                                       | `EmailSender` (outbox), `MailTransport` (SMTP/log) e processador com tentativas (`email_outbox`)                                             |
| `maintenance`                                         | Limpeza periódica de dados expirados (convites, tokens e outbox)                                                                             |
| `shared`                                              | Código comum (por agora, `/api/v1/ping`)                                                                                                     |
| `athletes`                                            | Atletas, encarregados de educação e ligação a contas, com autorização por recurso                                                            |
| `teams`                                               | Base mínima de equipas: CRUD, staff e plantel (épocas/escalões ficam para o módulo completo)                                                  |
| `training`, `matches`, `billing`                      | Módulos da v1, ainda vazios                                                                                                                  |

Migrações Flyway em `src/main/resources/db/migration`:
`V1` esquema base, `V2` RLS/permissões, `V3` refresh tokens, `V4` convites/reset de password,
`V5` visibilidade de `app_user` por membership, `V6` outbox de e-mail/permissões de provisionamento,
`V7` função `provision_tenant`, `V8` função/índices de limpeza periódica, `V9` atletas (conta, estado e
encarregados) e base mínima de equipas (staff e plantel) e `V10` remoção não destrutiva (anonimização e
índices de unicidade parciais). Papéis em `docker/postgres-init/01-roles.sql`,
também usado pelos testes (passwords `dev`, só para desenvolvimento; o `volei_migrator` tem `BYPASSRLS`).

## Por fazer

- MFA para administradores (fase 0).
- Restantes módulos da fase 1: equipas completo (épocas, escalões, plantéis por época), treinos, jogos e
  financeiro. Cada módulo deve reutilizar o padrão de autorização por recurso iniciado nos atletas.
- Alargar a autorização por recurso aos restantes módulos (por exemplo, o `FINANCEIRO` só vê as quotas que gere).
