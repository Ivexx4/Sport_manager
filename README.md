# Gestão de Clubes de Voleibol

Monólito modular (Java 25, Spring Boot 4.1.2, Maven) com PostgreSQL e multitenancy por `tenant_id` + Row Level Security.
Estado atual: **fases 0 a 3 entregues no backend** — **atletas** (CRUD, documentos com validade e importação CSV),
**equipas** (com épocas, escalões e regra federativa FPV), **treinos e jogos** (sessões, presenças, jogos,
resultados por set e convocatórias) e **financeiro** (planos de quotas, cobranças, pagamentos e lançamentos,
com geração automática de quotas a partir dos planos).
Inclui ainda estatísticas de assiduidade/convocatórias por atleta, alertas de caducidade de documentos por e-mail
e notificação das convocatórias por e-mail.
Faltam a PWA/notificações push, a integração real de pagamentos e a fase 4 (RGPD e endurecimento):
tenancy com RLS, papéis da base de dados, filtro de tenant, testes de isolamento, autenticação por JWT próprio,
limitação de tentativas de login, convites/utilizadores, recuperação de password, provisionamento de clubes,
e-mail por outbox (com tentativas), limpeza periódica de dados expirados, CI e CD para staging; atletas,
encarregados de educação, equipas, épocas e escalões com autorização por recurso.

## Requisitos

- JDK 25 (o `pom.xml` fixa `java.version` a 25)
- Maven 3.6.3+ — dispensável se usares o wrapper incluído (`./mvnw` em Bash, `.\mvnw.cmd` no
  PowerShell), que descarrega a versão fixada em `.mvn/wrapper/maven-wrapper.properties`
- Docker (PostgreSQL 16 local, Mailpit, Testcontainers e imagem de staging)

## Arranque

```bash
docker compose up -d                              # PostgreSQL 16 (volei_migrator/volei_app) + Mailpit (SMTP/UI)
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run # perfil dev: segredo JWT, SMTP local e bootstrap key
```

Em WSL/Linux, executa exatamente o segundo comando com o prefixo
`SPRING_PROFILES_ACTIVE=dev`. Se executares apenas `./mvnw spring-boot:run`, a aplicação usa o
perfil `default` e exige `JWT_SECRET` no ambiente:

```bash
export JWT_SECRET="$(openssl rand -base64 48)"
./mvnw spring-boot:run
```

### Lançamento completo (ambiente local)

Na primeira execução, instalar **JDK 25**, **Maven 3.6.3 ou superior**, **Node.js 20 ou
superior** e **Docker Desktop** com o motor Docker em execução. A aplicação usa PostgreSQL
16; não é necessário instalar PostgreSQL localmente.

1. Abrir um terminal na raiz do repositório e iniciar a infraestrutura:

   ```bash
   docker compose up -d
   docker compose ps
   ```

   O `db` expõe PostgreSQL em `localhost:5432` e o `mailpit` expõe a caixa de correio em
   [http://localhost:8025](http://localhost:8025). Na primeira inicialização são criados os
   papéis `volei_migrator` e `volei_app`; o Spring Boot executa automaticamente todas as
   migrações Flyway.

2. Gerar um segredo JWT para qualquer ambiente que não seja `dev`:

   ```bash
   openssl rand -base64 48
   ```

   Guardar o resultado como `JWT_SECRET`. No perfil `dev` é usado um segredo local pré-configurado
   e a chave de provisionamento é `dev-bootstrap-key`; estes valores não devem ser usados em
   produção.

3. Iniciar a API:

   ```bash
   # Linux/macOS/Git Bash
   SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

   # PowerShell (o wrapper é um script de shell; usar o .cmd)
   $env:SPRING_PROFILES_ACTIVE = "dev"
   .\mvnw.cmd spring-boot:run
   ```

   A API fica disponível em [http://localhost:8080](http://localhost:8080). Confirmar o arranque
   com:

   ```bash
   curl http://localhost:8080/api/v1/ping
   ```

4. Criar dados de demonstração **ou** um clube vazio:

   ```bash
   # Opção A: seed completo, incluindo clube-piloto e administrador
   docker compose exec -T db psql -U postgres -d volei < docker/dev-seed.sql

   # Opção B: gerar um token e provisionar um clube através da API
   TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/platform/bootstrap-key | jq -r .bootstrapKey)
   curl -s -X POST http://localhost:8080/api/v1/platform/tenants \
     -H "Content-Type: application/json" -H "X-Bootstrap-Key: $TOKEN" \
     -d '{"name":"Clube Piloto 2","slug":"clube-piloto-2","adminEmail":"admin@clube-piloto-2.local","adminName":"Admin","adminPassword":"admin12345!"}'
   ```

5. Instalar e iniciar o frontend num segundo terminal:

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

   Abrir [http://clube-piloto.localhost:5173](http://clube-piloto.localhost:5173) e entrar com
   `admin@clube-piloto.local` / `admin123` quando tiver sido usado o seed. Para uma API diferente,
   definir `VITE_API_URL` antes de `npm run dev` ou `npm run build`.

Para parar a execução sem apagar dados:

```bash
docker compose down
```

Para recomeçar a base de dados local do zero (**apaga todos os dados locais**):

```bash
docker compose down -v
docker compose up -d
```

### Verificação, testes e build

```bash
# Backend: compilação
./mvnw -DskipTests package

# Backend: testes unitários e de integração (requer Docker/Testcontainers)
./mvnw test

# Backend: relatório de cobertura em target/site/jacoco
./mvnw test

# Frontend: verificação TypeScript e build de produção
cd frontend
npm run build
```

O perfil `coverage-check` impõe os limiares de cobertura configurados no `pom.xml`:

```bash
./mvnw -Pcoverage-check verify
```

### Configuração fora de `dev`

Em staging/produção, definir pelo menos `JWT_SECRET`, `DB_APP_PASSWORD`,
`DB_MIGRATOR_PASSWORD` e `APP_PLATFORM_BOOTSTRAP_KEY` através do gestor de secrets. Também
configurar `APP_BASE_DOMAIN`, `APP_WEB_URL_TEMPLATE` e SMTP (`SPRING_MAIL_HOST`,
`SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` e `APP_MAIL_FROM`).
Não expor PostgreSQL nem a chave de bootstrap à Internet; o proxy deve preservar o subdomínio
do clube no cabeçalho `Host`/`X-Forwarded-Host`.

### Frontend web

O frontend React/TypeScript está em `frontend/`. Depois do login, apresenta uma visão geral
do clube e navegação para atletas, equipas, épocas e escalões, treinos, jogos e convocatórias
e quotas/financeiro. Os módulos de atletas, equipas e épocas/escalões incluem formulários de
criação; a listagem de atletas permite pesquisar, abrir o detalhe, desativar e importar um
ficheiro CSV UTF-8.

Na criação de uma equipa, a época e o escalão são obrigatórios. O catálogo federal de
escalões é comum a todos os clubes e só de leitura; ao mudar de época, o escalão é limpo.
A equipa criada fica assim associada à época e ao escalão que aparecem no cartão da equipa.

Os escalões são o catálogo oficial FPV, comum a todos os clubes e mantido por migrações da
base de dados. Usam limites de idade (não anos de nascimento), não podem ser criados ou
alterados na interface e incluem Minis A/B, Infantis, Iniciados, Cadetes, Juvenis, Juniores,
Sub21, Seniores e Veteranos A/B/C. Os escalões JB1 e JB não são criados. Ao criar uma época,
a data de referência da idade é preenchida por defeito com 1 de janeiro do ano seguinte ao
ano atual e pode ser ajustada no formulário; as validações calculam a idade exata nessa data.
Na mesma página, a gestão vê as autorizações de subida de escalão da época selecionada e pode
conceder (`Nova autorização`), revogar e reativar; os erros do servidor (`400`/`409`) aparecem
no aviso com a mensagem devolvida pela API.

Os módulos de treinos, jogos e quotas permitem criar sessões, jogos e planos quando o
utilizador tem permissões de gestão. O módulo financeiro inclui quotas recorrentes e taxas
únicas por atleta, geração automática de cobranças periódicas (com modo de simulação),
atualização do estado das cobranças, registo de pagamentos por método, mapa de pendentes,
saldo contabilístico e lançamentos de receitas/despesas. As promoções podem abranger todas
as cobranças de um plano (`applicationScope: ALL`) ou apenas uma cobrança (`SINGLE`),
identificada por `billingPeriod` no formato `AAAA-MM`. O papel `FINANCEIRO` tem acesso de
gestão financeira. Uma desistência de atleta é registada em
`POST /api/v1/athletes/{athleteId}/withdrawal` com `withdrawalDate`; as cobranças recorrentes
pendentes a partir do mês seguinte são automaticamente anuladas, mantendo-se o histórico.
gestão a este módulo.
Os pagamentos reais por webhook de Multibanco/MB WAY continuam dependentes da integração
com o agregador. A interface adapta-se a ecrãs pequenos com menu lateral colapsável.
As áreas ainda não implementadas no backend aparecem desativadas no menu.

Ao gerar cobranças para um plano recorrente associado a uma época com datas de início e fim,
o sistema cria antecipadamente todas as mensalidades/trimestres/anuidades dessa época como
`PENDING`, cada uma com a sua data de vencimento. Repetir a geração não duplica cobranças.

Em desenvolvimento, arranca-o em paralelo com a API:

```bash
cd frontend
npm install
npm run dev
```

Como o backend resolve o clube pelo subdomínio, abre o frontend com o subdomínio do
clube-piloto: [http://clube-piloto.localhost:5173](http://clube-piloto.localhost:5173).
Usa `admin@clube-piloto.local` / `admin123` com o seed de desenvolvimento. Para outra
API, define `VITE_API_URL` antes do `npm run dev` ou `npm run build`.

Há duas formas de ter um clube em desenvolvimento (escolhe uma):

**A. Dados de exemplo (seed)** — `clube-piloto`, com `admin@clube-piloto.local` / `admin123`:

```bash
docker compose exec -T db psql -U postgres -d volei < docker/dev-seed.sql
```

**B. Provisionamento pela API** — cria um clube novo e o primeiro `ADMIN` (ver secção seguinte).

- **E-mail em dev:** o Mailpit recebe os e-mails em `localhost:1025`; interface web em [http://localhost:8025](http://localhost:8025).
- **Se a base de dados local não arrancar** com `database files are incompatible with server`, o volume `pgdata` foi criado por outra versão principal do PostgreSQL: usa `docker compose down -v` (apaga os dados locais) ou alinha a imagem `postgres` no compose com a versão do volume.

Fora do perfil `dev`, a variável `JWT_SECRET` é obrigatória (Base64, 32 bytes ou mais): `openssl rand -base64 48`.
O provisionamento de novos clubes requer um token de utilização única gerado pelo backend através
de `POST /api/v1/platform/bootstrap-key`; este endpoint só aceita ligações locais
(`127.0.0.1`/`::1`) e devolve o token uma única vez na resposta. A interface obtém o token
automaticamente ao abrir o formulário de criação e nunca o pede ao utilizador. A variável
`APP_PLATFORM_BOOTSTRAP_KEY` continua disponível como fallback de operação local/dev, mas deve
ser removida em produção.

### Provisionamento de um clube

```bash
curl -s -X POST http://localhost:8080/api/v1/platform/tenants \
  -H 'Content-Type: application/json' \
  -H 'X-Bootstrap-Key: dev-bootstrap-key' \
  -d '{"name":"Clube Piloto 2","slug":"clube-piloto-2","adminEmail":"admin@clube-piloto-2.local","adminName":"Admin","adminPassword":"admin12345!"}'
```

Para gerar um token localmente, sem o guardar no código:

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/platform/bootstrap-key
```

Usa o valor `bootstrapKey` devolvido no cabeçalho `X-Bootstrap-Key` ao criar um único clube.
Depois de uma criação bem-sucedida, o token deixa de ser válido e é necessário gerar outro.

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

## Arquitetura e stack

Monólito modular com PostgreSQL e multitenancy por `tenant_id` + Row Level Security.

| Camada | Escolha | Porquê |
| :--- | :--- | :--- |
| **Linguagem** | Java 25 (LTS) | Última LTS, suportada pelo Spring Boot 4.1; virtual threads, records, pattern matching |
| **Framework** | Spring Boot 4.1.2 (Web, Security, Data JPA) sobre Spring Framework 7 | Ecossistema maduro, segurança e multitenancy bem documentados |
| **Base de dados** | PostgreSQL 16+ | Row Level Security nativa, JSONB, particionamento |
| **Migrações** | Flyway | Versionar esquema e políticas RLS como código |
| **Pool de ligações** | HikariCP (+ PgBouncer em produção) | Atenção ao modo de pooling com RLS (ver Multitenancy) |
| **Autenticação** | Keycloak ou Spring Authorization Server (OIDC/JWT) | Utilizadores com papéis por clube; SSO futuro |
| **API** | REST + OpenAPI (springdoc) | Contrato claro para web e mobile |
| **Frontend** | React + TypeScript (PWA) | Pais e atletas usam o telemóvel; PWA evita duas apps nativas na v1 |
| **Tarefas assíncronas** | Spring Scheduler + fila (RabbitMQ ou tabela outbox) | Notificações, geração de quotas, lembretes |
| **Pagamentos** | MB WAY / Multibanco via agregador (ex.: Stripe, SIBS ou EasyPay) | Referências Multibanco e MB WAY são o padrão em Portugal |
| **Observabilidade** | Micrometer + Prometheus/Grafana, logs JSON com `tenant_id` | Diagnóstico por clube |
| **Testes** | JUnit 5, Testcontainers (PostgreSQL real), ArchUnit | Testar RLS contra a base real, não mocks |

**Estrutura de pacotes (módulos):** `tenancy`, `identity`, `athletes`, `teams`, `training`, `matches`, `billing`, `notifications`, `shared`. Cada módulo expõe apenas uma interface pública; ArchUnit impede dependências cíclicas.

### Notas sobre Java 25 e Spring Boot 4.1.2:
- **Requisitos da documentação oficial:** Java 17 a 27 (Java 25 suportado), Spring Framework 7.0.10 ou superior, Maven 3.6.3+ ou Gradle 8.14+/9.x, Tomcat 11 embebido (Servlet 6.1).
- **Estrutura do Spring Boot 4.0:** O Spring Boot 4.0 foi uma rutura face ao 3.x: base Jakarta EE 11, Jackson 3, starters modulares (JARs de auto-configuração divididos) e null safety com JSpecify. Vê o resumo do 4.1 na InfoQ e o guia de migração oficial antes de copiar exemplos escritos para o 3.x.
- **Geração de código:** Muito material online (e muito código gerado por IA) ainda assume Spring Boot 3: ao pedir código, indicar sempre a versão exata e validar as dependências e os nomes dos starters contra a documentação do 4.1.
- **Segurança nas migrações:** Os testes de integração com Testcontainers e RLS continuam a ser a rede de segurança para migrações de versão.

---

## Autenticação

| Endpoint                    | Descrição                                                                                                               |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `POST /api/v1/auth/login`   | `{email, password}` → tokens; com MFA pede um segundo passo (ver abaixo); `401` em falha, `429` após demasiadas falhas |
| `POST /api/v1/auth/refresh` | `{refreshToken}` → novo par de tokens (rotação: cada refresh token só serve uma vez)                                    |
| `POST /api/v1/auth/logout`  | `{refreshToken}` → 204; revoga o refresh token                                                                          |
| `GET /api/v1/auth/me`       | Utilizador, clube e papéis do token                                                                                     |

O login devolve `{accessToken, refreshToken, tokenType, expiresIn}` quando a sessão fica concluída. Com autenticação
multifator, devolve antes um desafio (sem tokens): `{mfaRequired, mfaEnrollmentRequired, challengeToken, expiresIn}`.

### Autenticação multifator (TOTP)

| Endpoint                                  | Quem              | Descrição                                                                                     |
|-------------------------------------------|-------------------|-----------------------------------------------------------------------------------------------|
| `POST /api/v1/auth/mfa/verify`            | público           | `{challengeToken, code}` → tokens; o `code` é TOTP de 6 dígitos ou um código de recuperação    |
| `POST /api/v1/auth/mfa/enrollment/setup`  | público           | `{challengeToken}` → `{secret, otpauthUri}`; só com desafio de inscrição                      |
| `POST /api/v1/auth/mfa/enrollment/confirm`| público           | `{challengeToken, code}` → tokens + `{recoveryCodes[]}`; os códigos só aparecem nesta resposta |
| `GET /api/v1/auth/mfa`                    | autenticado       | `{enabled, pending, recoveryCodesLeft}` da própria conta                                      |
| `POST /api/v1/auth/mfa/setup`             | autenticado       | `{secret, otpauthUri}`; início da inscrição opcional                                          |
| `POST /api/v1/auth/mfa/activate`          | autenticado       | `{code}` → `{recoveryCodes[]}`; confirma a inscrição pendente                                 |
| `POST /api/v1/auth/mfa/disable`           | autenticado       | `{password, code}` → 204; exige a password e um código válido                                 |

- **Obrigatório para `ADMIN`:** por omissão (`app.security.mfa.require-for-admins`, `APP_SECURITY_MFA_REQUIRE_FOR_ADMINS`),
  um `ADMIN` sem MFA recebe `mfaEnrollmentRequired: true` no login e só entra depois de inscrever e confirmar o
  autenticador. Os restantes papéis entram normalmente e podem ativar o MFA quando quiserem.
- **TOTP (RFC 6238):** 6 dígitos, passo de 30 s, HMAC-SHA1 e segredo de 160 bits em Base32 — compatível com Google
  Authenticator, 1Password, Authy, etc. A verificação tolera um passo para cada lado (`APP_SECURITY_MFA_TIME_STEP_SKEW`)
  e guarda em `app_user.mfa_last_used_step` o último passo aceite, pelo que o mesmo código não serve duas vezes.
- **Desafio de login:** em vez de uma tabela de desafios, o login devolve um JWT de 5 minutos
  (`APP_SECURITY_MFA_CHALLENGE_TTL`) assinado com a mesma chave, marcado com `purpose=mfa_challenge` e
  `mfa_flow=VERIFY|ENROLL`, ligado ao `tenant_id` do clube. Não é um token de sessão: o validador de tokens de acesso
  recusa qualquer token com `purpose`, e um desafio do clube A não serve no clube B.
- **Códigos de recuperação:** 10 por omissão (`APP_SECURITY_MFA_RECOVERY_CODE_COUNT`), de uso único, guardados só como
  hash SHA-256 em `mfa_recovery_code`. É uma tabela de plataforma (sem `tenant_id`/RLS) porque o MFA pertence à conta e
  os códigos têm de servir em qualquer clube. Mostram-se uma única vez, ao ativar.
- **Desativar:** exige a password **e** um código válido, para que uma sessão roubada não chegue para desligar o MFA.
- **Tentativas:** os passos do MFA usam o mesmo limitador de força bruta do login (clube + IP), e o segundo passo é o
  que limpa o contador. Todos os passos ficam em `audit_log` (`MFA_ENROLLMENT_REQUIRED`, `MFA_ENROLLMENT_STARTED`,
  `MFA_ENROLLED`, `MFA_VERIFIED`, `MFA_VERIFICATION_FAILED`, `MFA_DISABLED`, `MFA_DISABLE_FAILED`).

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
| `POST /api/v1/platform/tenants`              | token único `X-Bootstrap-Key` | `{name, slug, adminEmail, adminName, adminPassword}` → 201; cria clube e 1.º `ADMIN` |
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
(equipa, staff e plantel); o módulo `teams` completo (equipas, staff, plantel, épocas, escalões e regra federativa
FPV) foi entregue.

### Endpoints de atletas

| Endpoint                                        | Quem pode                                                                 | Descrição                                                                 |
|-------------------------------------------------|---------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `GET /api/v1/athletes`                          | `ADMIN`, `DIRECAO`, `FINANCEIRO` (todos) e `TREINADOR`/`ATLETA`/`ENCARREGADO` (âmbito) | Lista paginada: `q` (nome), `status`, `teamId`, `page` (≥0), `size` (1–100, omissão 20) |
| `GET /api/v1/athletes/{id}`                     | idem                                                                      | Detalhe; fora do âmbito devolve `404`                                     |
| `POST /api/v1/athletes`                         | `ADMIN`, `DIRECAO`                                                        | Cria; `409` para licença repetida ou conta já ligada                      |
| `PUT /api/v1/athletes/{id}`                     | `ADMIN`, `DIRECAO`, e o âmbito de `TREINADOR`/`ATLETA`/`ENCARREGADO`      | Atualiza os dados; fora do âmbito de leitura `404`, dentro mas sem escrita `403`; mudar a `birthDate` revalida inscrições e plantéis (`400`) |
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
| `POST /api/v1/teams`                          | `ADMIN`, `DIRECAO`                 | `{name, status?, seasonId?, ageGroupId?}`; nome único por clube (sem distinguir maiúsculas); época/escalão têm de existir no clube (`404`) |
| `PUT /api/v1/teams/{id}`                      | `ADMIN`, `DIRECAO`                 | Atualiza nome/estado e associações; `null` em `seasonId`/`ageGroupId` remove a associação; mudar a época ou o escalão revalida o plantel (`400`) |
| `DELETE /api/v1/teams/{id}`                   | `ADMIN`, `DIRECAO`                 | Desativa e anonimiza; staff e plantel ficam preservados para histórico |
| `GET /api/v1/teams/{id}/staff`                | mesma visibilidade da equipa       | Lista treinadores/adjuntos                                       |
| `POST /api/v1/teams/{id}/staff`               | `ADMIN`, `DIRECAO`                 | `{userId, role?}`: `TREINADOR` ou `TREINADOR_ADJUNTO`            |
| `DELETE /api/v1/teams/{id}/staff/{userId}`    | `ADMIN`, `DIRECAO`                 | Remove o staff                                                   |
| `GET /api/v1/teams/{id}/athletes`             | mesma visibilidade da equipa       | Lista o plantel                                                  |
| `POST /api/v1/teams/{id}/athletes`            | `ADMIN`, `DIRECAO`                 | `{athleteId}`; atleta do mesmo clube, com a idade válida para o escalão (regra federativa FPV) |
| `DELETE /api/v1/teams/{id}/athletes/{athleteId}` | `ADMIN`, `DIRECAO`               | Remove do plantel                                                |

A gestão de staff e plantel é **exclusiva de `ADMIN`/`DIRECAO`**: um treinador não pode adicionar atletas à
própria equipa para alargar o seu âmbito de autorização.

### Regra federativa FPV no plantel

A idade do atleta é sempre calculada na **data de referência da época** (`season.cutoff_date`;
sem ela, `season.start_date`; sem nenhuma das duas, a data de hoje) e comparada com os limites
do escalão (`age_group.min_age`/`max_age`). A regra vive numa só classe (`AgeGroupEligibility`)
e é aplicada em três pontos: o plantel (`POST /api/v1/teams/{teamId}/athletes`, contra o escalão
da equipa), a escolha do escalão de inscrição do atleta na época e a revalidação de estados já
guardados.

Com o atleta no clube e ainda fora do plantel (`404`/`409`), o resultado é:

| Idade vs. escalão | Resultado |
|---|---|
| `min_age <= idade <= max_age` | **permite** |
| `idade < min_age` (atleta **mais novo**, subida) | exige uma **autorização excepcional** `ACTIVE` em `season_promotion` para a época × atleta × escalão; sem ela → `400` "Subida de escalão exige autorização excepcional para a época" |
| `idade > max_age` (atleta **mais velho**, descida) | **`400` sempre**, mesmo com autorização: "Proibido atuar em escalão inferior conforme regulamento FPV" |
| `max_age` nulo (Seniores, Veteranos) | não tem limite superior; a idade mínima continua a mandar (um atleta de 18 anos em Seniores é uma subida) |

- A equipa tem de estar associada a uma época e a um escalão (`400` se não estiver).
- Uma autorização só desbloqueia o caso "mais novo"; nunca permite descer de escalão.
- Conceder é declarativo: não se verifica se o atleta precisa da autorização nem se impede que o
  mesmo atleta tenha autorizações para escalões diferentes na mesma época.

### Revalidação de estados guardados

Alterar os dados que servem de base à regra não pode criar estados ilegais. Quando o dado
relevante muda, a escrita é seguida — na mesma transação, com rollback em caso de falha — de uma
verificação de tudo o que já está guardado (`SeasonEligibility`):

| Alteração | O que é revalidado |
|---|---|
| `PUT /api/v1/athletes/{id}` que muda a `birthDate` | inscrições na época e plantéis do atleta |
| `PUT /api/v1/seasons/{id}` que muda `startDate`/`cutoffDate` | inscrições e plantéis da época |
| `PUT /api/v1/teams/{id}` que muda `seasonId`/`ageGroupId` | plantel da equipa |

A primeira violação devolve `400` com "A alteração tornaria o atleta inelegível em `<escalão>`
(`<contexto>`)". Não há override deliberado: para aplicar uma alteração legítima é preciso
resolver primeiro o estado, concedendo as autorizações necessárias ou removendo o atleta do
plantel. Uma época sem `cutoffDate` é validada no `startDate` (e na data de hoje se também não
tiver início); ao criar uma época, a data de referência em falta é preenchida com 1 de janeiro
do ano seguinte.

### Endpoints de épocas, escalões e autorizações de subida

| Endpoint                                            | Quem pode          | Descrição                                                                 |
|-----------------------------------------------------|--------------------|---------------------------------------------------------------------------|
| `GET /api/v1/seasons`                               | qualquer membro    | Lista as épocas do clube                                                  |
| `GET /api/v1/seasons/{id}`                          | qualquer membro    | Detalhe; `404` se não existir no clube                                    |
| `POST /api/v1/seasons`                              | `ADMIN`, `DIRECAO` | `{name, startDate?, endDate?, cutoffDate?, isActive?}` → 201              |
| `PUT /api/v1/seasons/{id}`                          | `ADMIN`, `DIRECAO` | Atualiza a época; nome único por clube; mudar as datas revalida inscrições e plantéis (`400`) |
| `DELETE /api/v1/seasons/{id}`                       | `ADMIN`, `DIRECAO` | Anonimiza a época (`isActive = false` + `anonymized_at`); as equipas associadas mantêm-se |
| `GET /api/v1/age-groups`                            | qualquer membro    | Lista o catálogo federal FPV (comum a todos os clubes)                    |
| `GET /api/v1/age-groups/{id}`                       | qualquer membro    | Detalhe do escalão                                                        |
| `GET /api/v1/seasons/{seasonId}/promotions`         | `ADMIN`, `DIRECAO` | Autorizações da época; `?athleteId=` filtra por atleta                    |
| `POST /api/v1/seasons/{seasonId}/promotions`        | `ADMIN`, `DIRECAO` | `{athleteId, ageGroupId, reason?}` → 201; `404` fora do clube; `409` se já existir uma `ACTIVE` |
| `PATCH /api/v1/seasons/{seasonId}/promotions/{id}`  | `ADMIN`, `DIRECAO` | `{status}`: `ACTIVE`/`REVOKED` → 204                                      |

`isActive` marca a época corrente; `startDate`/`endDate` são opcionais, mas a data de fim não pode ser anterior à de início. `cutoffDate` é a data de referência da idade: quando não é indicada é preenchida com o `startDate` e, sem este, com 1 de janeiro do ano seguinte. As épocas anonimizadas deixam de aparecer nas leituras e não podem ser associadas a novas equipas.

Os escalões (`age_group`) são o catálogo federal FPV, mantido por migrações e igual para todos os clubes: limites de idade (`min_age`/`max_age`) e sem `tenant_id`. São **só de leitura** pela API — `POST`/`PUT`/`DELETE /api/v1/age-groups` já não existem e devolvem `405`.

A autorização (`season_promotion`) é por época × atleta × escalão, guarda `reason` (até 200 caracteres, opcional) e `authorized_by`. Um `POST` sobre uma autorização já revogada reativa a mesma linha em vez de criar outra. Revogar **não remove** o atleta do plantel nem a inscrição: só bloqueia as operações seguintes que dependam da autorização. Todas as alterações ficam em `audit_log` (`SEASON_PROMOTION_GRANTED`, `SEASON_PROMOTION_REVOKED`).

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
- Todas as operações de escrita ficam em `audit_log` (`ATHLETE_*`, `TEAM_*` e `SEASON_PROMOTION_*`).
- As autorizações de subida de escalão (`/api/v1/seasons/{id}/promotions`) são exclusivas de `ADMIN`/`DIRECAO`
  nas três rotas: `TREINADOR`, `FINANCEIRO`, `ATLETA` e `ENCARREGADO` recebem `403`, mesmo para ler.

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

Exceções: o catálogo federal de escalões (`age_group`) não pertence ao clube — é global, só de leitura e
mantido por migrações — pelo que não tem `DELETE`. As autorizações de subida de escalão mudam de estado
(`ACTIVE`/`REVOKED`) em vez de desaparecerem, e revogar **não remove** as linhas de `team_athlete` nem de
`athlete_season_registration`: apenas bloqueia as operações seguintes que dependam da autorização.

### Documentos, importação e estatísticas de atletas

| Endpoint                                        | Quem pode                                                              | Descrição                                                              |
|-------------------------------------------------|------------------------------------------------------------------------|------------------------------------------------------------------------|
| `GET /api/v1/athletes/{athleteId}/documents`    | mesma visibilidade do atleta                                           | Lista os documentos do atleta                                          |
| `POST /api/v1/athletes/{athleteId}/documents`   | `ADMIN`/`DIRECAO`, `TREINADOR` (atletas das suas equipas), `ATLETA`/`ENCARREGADO` (próprios) | `{type, fileName?, fileUrl?, validUntil?}` → 201; `FINANCEIRO` só lê (`403` na escrita) |
| `PUT /api/v1/documents/{documentId}`            | idem                                                                   | Atualiza; uma nova validade reinicia os alertas de caducidade          |
| `DELETE /api/v1/documents/{documentId}`         | idem                                                                   | Anonimiza o documento, preservando o registo                           |
| `GET /api/v1/athletes/{athleteId}/stats`        | mesma visibilidade do atleta                                           | Convocatórias, jogos disputados, respostas e presenças em treinos      |
| `POST /api/v1/athletes/import`                  | `ADMIN`, `DIRECAO`                                                     | `multipart/form-data` com `file` CSV → `{imported: n}`                 |

O alerta de caducidade de documentos corre todos os dias às 08:00, avisa com 30 dias de antecedência e não
repete o mesmo documento antes de 7 dias (ou até a validade ser alterada). O e-mail vai para o endereço do
atleta e usa a outbox, pelo que beneficia das tentativas e do transporte configurado.

### Endpoints de locais e treinos

| Endpoint                                                  | Quem pode                                              | Descrição                                                          |
|-----------------------------------------------------------|--------------------------------------------------------|--------------------------------------------------------------------|
| `GET /api/v1/venues`                                      | qualquer membro                                        | Lista os locais (pavilhões/campos)                                 |
| `GET /api/v1/venues/{id}`                                 | qualquer membro                                        | Detalhe; `404` se não existir no clube                             |
| `POST /api/v1/venues`                                     | `ADMIN`, `DIRECAO`                                     | `{name, address?}` → 201; nome único por clube sem distinguir maiúsculas (`409`) |
| `PUT /api/v1/venues/{id}`                                 | `ADMIN`, `DIRECAO`                                     | Atualiza                                                            |
| `DELETE /api/v1/venues/{id}`                              | `ADMIN`, `DIRECAO`                                     | Anonimiza o local                                                   |
| `GET /api/v1/training-sessions`                           | gestão/`TREINADOR`, e `ATLETA`/`ENCARREGADO` (âmbito)  | Lista; filtro opcional `teamId`; `FINANCEIRO` não tem acesso        |
| `GET /api/v1/training-sessions/{id}`                      | visibilidade da equipa da sessão                       | Detalhe; fora do âmbito devolve `404`                              |
| `POST /api/v1/training-sessions`                          | `ADMIN`/`DIRECAO`, `TREINADOR` (só nas suas equipas)   | `{teamId, courtId?, startTime, endTime, recurrence?, notes?, status?}` → 201 |
| `PUT /api/v1/training-sessions/{id}`                      | idem                                                   | Atualiza; `endTime` tem de ser posterior a `startTime`             |
| `DELETE /api/v1/training-sessions/{id}`                   | idem                                                   | Anonimiza a sessão (as presenças ficam preservadas)                |
| `GET /api/v1/training-sessions/{id}/attendance`           | visibilidade da sessão                                 | Lista presenças                                                     |
| `POST /api/v1/training-sessions/{id}/attendance`          | gestão/`TREINADOR` da equipa                           | `{athleteId, status?, justification?}` → 204 (upsert)              |
| `PUT /api/v1/training-sessions/{id}/attendance/{athleteId}` | idem                                                 | Upsert por caminho (`{status?, justification?}` opcional)           |
| `DELETE /api/v1/training-sessions/{id}/attendance/{athleteId}` | idem                                              | Remove a presença                                                  |

Estados de sessão: `ACTIVE`/`CANCELLED`/`COMPLETED`. Presenças: `PRESENT`/`ABSENT`/`JUSTIFIED`/`LATE`.
Para compatibilidade com os clientes iniciais, `courtId` também aceita `venueId`, e `startTime`/`endTime`
aceitam `start`/`end` (ou `startDateTime`/`endDateTime`).

### Endpoints de jogos

| Endpoint                                             | Quem pode                                              | Descrição                                                              |
|------------------------------------------------------|--------------------------------------------------------|------------------------------------------------------------------------|
| `GET /api/v1/matches`                                | gestão/`TREINADOR`, e `ATLETA`/`ENCARREGADO` (âmbito)  | Lista; filtro opcional `teamId`                                        |
| `GET /api/v1/matches/{id}`                           | visibilidade da equipa do jogo                         | Detalhe; fora do âmbito devolve `404`                                  |
| `POST /api/v1/matches`                               | `ADMIN`/`DIRECAO`, `TREINADOR` (só nas suas equipas)   | `{teamId, opponent, matchDate, competition?, location?, courtId?, isHome?, status?}` → 201 |
| `PUT /api/v1/matches/{id}`                           | idem                                                   | Atualiza                                                               |
| `DELETE /api/v1/matches/{id}`                        | idem                                                   | Anonimiza o jogo (sets e convocatórias preservados)                    |
| `GET /api/v1/matches/{id}/set-scores`                | visibilidade do jogo                                   | Lista os sets                                                          |
| `POST /api/v1/matches/{id}/set-scores`               | gestão/`TREINADOR` da equipa                           | `{setNumber, ourPoints, opponentPoints}` → 204 (upsert)                |
| `PUT /api/v1/matches/{id}/set-scores/{setNumber}`    | idem                                                   | Upsert por caminho                                                     |
| `DELETE /api/v1/matches/{id}/set-scores/{setNumber}` | idem                                                   | Remove o set                                                           |
| `GET /api/v1/matches/{id}/call-ups`                  | visibilidade do jogo                                   | Lista as convocatórias e respostas                                     |
| `POST /api/v1/matches/{id}/call-ups`                 | gestão/`TREINADOR` da equipa                           | `{athleteId}` → 204; atleta tem de estar no plantel; `409` se repetido |
| `DELETE /api/v1/matches/{id}/call-ups/{athleteId}`   | idem                                                   | Remove a convocatória                                                  |
| `PATCH /api/v1/matches/{id}/call-ups/{athleteId}`    | gestão/`TREINADOR` da equipa ou o próprio atleta       | `{response}`: `CONFIRMED`/`DECLINED` (ou `null` para limpar) → 204     |

`isHome` também aceita `home`, e `courtId` aceita `venueId`. Estados do jogo:
`SCHEDULED`/`PLAYED`/`CANCELLED`/`POSTPONED`. O `ENCARREGADO` vê o jogo do educando mas não responde pela
convocatória (só o atleta ou a gestão/treinador o podem fazer). Ao criar uma convocatória, o sistema enfileira na
outbox um e-mail para o atleta e para os encarregados com e-mail (destinatários deduplicados; sem contactos não há
envio e a convocatória é criada na mesma).

## Financeiro (fase 3)

| Endpoint                                      | Quem pode                                      | Descrição                                                                 |
|-----------------------------------------------|------------------------------------------------|---------------------------------------------------------------------------|
| `GET /api/v1/fee-plans`                       | `ADMIN`, `DIRECAO`, `FINANCEIRO`               | Lista os planos de quotas                                                 |
| `POST /api/v1/fee-plans`                      | idem                                           | `{name, amountCents, seasonId?, ageGroupId?, periodicity?}` → 201; nome único (`409`) |
| `DELETE /api/v1/fee-plans/{id}`               | idem                                           | Anonimiza o plano                                                        |
| `GET /api/v1/charges`                         | gestão/`FINANCEIRO` (tudo) e `ATLETA`/`ENCARREGADO` (âmbito) | Lista; filtros `athleteId` e `status`                                    |
| `POST /api/v1/charges`                        | `ADMIN`, `DIRECAO`, `FINANCEIRO`               | `{athleteId, amountCents, dueDate, guardianId?, feePlanId?, status?}` → 201 |
| `POST /api/v1/charges/generate`               | idem                                           | `{period?, feePlanId?, dryRun?}` → `{period, created, skipped}` (200)    |
| `PATCH /api/v1/charges/{id}/status`           | idem                                           | `{status}` → 204                                                          |
| `DELETE /api/v1/charges/{id}`                 | idem                                           | Anonimiza a cobrança (pagamentos preservados)                             |
| `GET /api/v1/charges/{id}/payments`           | idem                                           | Lista os pagamentos da cobrança                                           |
| `POST /api/v1/payments`                       | idem                                           | `{chargeId, amountCents, method?, reference?}` → 201                      |
| `GET /api/v1/ledger-entries`                  | idem                                           | Lista receitas/despesas                                                   |
| `POST /api/v1/ledger-entries`                 | idem                                           | `{type, category, amountCents, entryDate, description?}` → 201            |
| `DELETE /api/v1/ledger-entries/{id}`          | idem                                           | Anonimiza o lançamento                                                    |

Valores em **cêntimos** (`amountCents`, inteiro ≥ 0). Periodicidade: `ONE_TIME`/`MONTHLY`/`QUARTERLY`/`YEARLY`;
estados de cobrança: `PENDING`/`PAID`/`OVERDUE`/`CANCELLED`; métodos de pagamento:
`CASH`/`MULTIBANCO`/`MBWAY`/`TRANSFER`/`OTHER`; tipo de lançamento: `INCOME`/`EXPENSE`. O `TREINADOR` não
entra no financeiro (`403`), o `ATLETA`/`ENCARREGADO` só vêem as cobranças que lhes pertencem e a gestão/`FINANCEIRO`
gere tudo.

### Geração automática de quotas

`POST /api/v1/charges/generate` cria as cobranças do período (`period`, formato `AAAA-MM`, por omissão o mês
atual em `Europe/Lisbon`) para todos os planos ativos, ou apenas para `feePlanId`. Regras:

- `MONTHLY` gera todos os meses; `QUARTERLY` só em janeiro/abril/julho/outubro (período canónico `AAAA-Qn`);
  `YEARLY` só em janeiro (período `AAAA`); `ONE_TIME` nunca gera automaticamente.
- Alvo: atletas `ACTIVE` e não anonimizados que pertençam a uma equipa `ACTIVE` e não anonimizada. Se o plano
  tiver `seasonId`/`ageGroupId`, a equipa tem de coincidir.
- Valor igual ao do plano no momento (snapshot); vencimento no dia 10 do mês que abre o período; menores de 18
  anos ficam com o primeiro encarregado associado como `guardianId`.
- **Idempotente**: o índice único parcial `charge (tenant_id, fee_plan_id, athlete_id, billing_period)` impede
  duplicar o mesmo período; repetir devolve `created: 0` e `skipped` com as cobranças já existentes.
- `dryRun: true` calcula `created`/`skipped` sem escrever. Planos sobrepostos geram cobranças distintas.
- O agendador `ChargeGenerator` corre no dia 1 de cada mês às 02:00 e faz o mesmo por clube, na sua própria
  transação.

## Modelo de dados

Todas as tabelas abaixo levam `tenant_id`, `id` (UUID), `created_at`, `updated_at` e `created_by`; as que não referem tenant (`tenant`, `app_user`, `mfa_recovery_code`) são geridas pela plataforma.

| Entidade | Campos principais | Relações |
| :--- | :--- | :--- |
| `tenant` | nome, slug, NIF, estado, plano | raiz de todos os dados |
| `app_user` | email, nome, estado | N:N com tenant via membership |
| `membership` | user_id, papel, estado | liga utilizador a clube com papel |
| `season` | nome, início, fim, data de referência da idade (`cutoff_date`), ativa | 1:N com team, inscrições e promoções |
| `age_group` | catálogo federal: `code`, nome, idade mín./máx. | global (sem `tenant_id`) e só de leitura; referenciado por team, inscrições, promoções e planos |
| `team` | nome, season_id, age_group_id, género | 1:N com roster_entry, training_session, match |
| `athlete` | nome, data de nascimento, género, NIF, contactos, nº licença, estado, conta | N:N com `athlete_guardian`; 1:N com document, attendance e call_up |
| `athlete_guardian` | athlete_id, user_id, parentesco | liga atleta a encarregado de educação |
| `team` | nome, season_id, age_group_id, género | 1:N com `team_athlete`, `team_staff`, training_session e match |
| `team_athlete` | team_id, athlete_id | plantel da equipa |
| `athlete_season_registration` | season_id, athlete_id, age_group_id | inscrição oficial do atleta na época |
| `season_promotion` | season_id, athlete_id, age_group_id, estado, motivo, `authorized_by` | autorização excepcional de subida, por época × atleta × escalão |
| `team_staff` | team_id, user_id, função | treinador, delegado |
| `document` | athlete_id, tipo, ficheiro, validade, `expiry_notified_at` | alerta de caducidade (com deduplicação semanal) |
| `venue` | nome, morada | pavilhão/campo usado por treinos e jogos |
| `training_session` | team_id, court_id, início, fim, recorrência, estado | 1:N com attendance |
| `attendance` | session_id, athlete_id, estado, justificação | |
| `match` | team_id, competição, adversário, local, court_id, data/hora, casa/fora, estado | 1:N com set_score e call_up |
| `set_score` | match_id, nº do set, pontos nossos, pontos adversário | |
| `call_up` | match_id, athlete_id, estado, resposta, data da resposta | convocatória (notificada por e-mail/outbox) |
| `fee_plan` | nome, season_id, age_group_id, valor, periodicidade | gera charges |
| `charge` | athlete_id, guardian_id, fee_plan_id, valor, vencimento, estado, billing_period | 1:N com payment; período faturado garante idempotência |
| `payment` | charge_id, método, valor, referência externa, data | reconciliação do agregador |
| `ledger_entry` | tipo, categoria, valor, data, descrição | receitas e despesas do clube |
| `audit_log` | utilizador, ação, entidade, antes/depois (JSONB), data | só de escrita (não há endpoint de leitura) |
| `mfa_recovery_code` | user_id, hash do código, usado em | códigos de recuperação de uso único (tabela de plataforma, sem RLS) |

### Regras de modelação:
- Valores monetários em inteiros (cêntimos) ou `numeric(12,2)`, nunca `float`.
- Datas e horas em `timestamptz`, guardadas em UTC e mostradas no fuso do clube (`Europe/Lisbon`).
- Um atleta pode ter várias épocas; o escalão é derivado da idade na data de referência da época e guardado na
  inscrição (`athlete_season_registration`) e na equipa, nunca no atleta.
- Apagar atletas faz-se por anonimização (RGPD), mantendo os registos financeiros exigidos por lei.
- A faturação da plataforma é uma mensalidade única por clube, igual para todos: sem planos, tiers ou preço
  personalizado. Cada fatura guarda o valor cobrado no momento (snapshot), para que mudar o preço não reescreva
  o histórico.

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
./mvnw test
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
- `MfaFlowTest`: MFA contra PostgreSQL real com o segundo fator obrigatório para `ADMIN`. Cobre a inscrição forçada
  no login (segredo + URI `otpauth://`, confirmação e códigos de recuperação), a recusa do token de desafio como
  token de acesso, o desafio de um clube no outro (`401`), o login com MFA ativo, a proteção de repetição do mesmo
  código, os códigos de recuperação de uso único, a ativação opcional por outro papel, a desativação com password
  e código e os registos de auditoria (`audit_log` só de escrita, sem endpoint de leitura).
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
  reutilização da licença e da conta. Inclui ainda as estatísticas por atleta (convocatórias, jogos disputados,
  respostas e presenças, com a mesma visibilidade da leitura do atleta) e a importação CSV (linhas válidas,
  licenças repetidas e datas inválidas ignoradas, repetição sem duplicar e escrita restrita à gestão).
- `TeamFlowTest`: CRUD de equipas, nome único, autorização da listagem, staff e plantel, isolamento entre clubes,
  remoção não destrutiva (a equipa é anonimizada e staff/plantel ficam preservados), o âmbito do `TREINADOR`
  (só vê as equipas em que é staff e não as gere) e a regra federativa FPV no plantel (faixa de escalão, atleta
  mais novo a exigir autorização — concedida pela própria API — e atleta mais velho proibido mesmo com ela).
  Cobre também a revalidação: mudar a data de referência da época, o escalão da equipa ou a data de nascimento
  de um atleta do plantel devolve `400` e não deixa a escrita aplicada (rollback).
- `PromotionFlowTest`: autorizações de subida de escalão. Conceder, listar (com filtro por atleta), revogar e
  reativar a mesma linha, `409` para duplicado ativo, `404` para época/atleta/escalão de outro clube, `403` para
  `TREINADOR` e `FINANCEIRO`, isolamento entre clubes, auditoria (`SEASON_PROMOTION_GRANTED`/`_REVOKED`) e a prova
  de que revogar não altera `team_athlete` nem `athlete_season_registration`.
- `SeasonAgeGroupFlowTest`: CRUD das épocas e catálogo federal de escalões só de leitura (`POST`/`PUT`/`DELETE`
  devolvem `405` e os dois clubes vêem exatamente a mesma lista), validações de datas, leitura aberta a qualquer
  membro, escrita de épocas só para `ADMIN`/`DIRECAO`, isolamento entre clubes, remoção não destrutiva e associação
  de `seasonId`/`ageGroupId` às equipas (incluindo referências de outro clube ou anonimizadas).
- `TrainingFlowTest`: locais e sessões de treino contra PostgreSQL real. Cobre CRUD, nome de local único (`409`),
  validações (`400`/`404`), presenças (upsert, listagem e remoção), autorização por papel (`TREINADOR` só nas suas
  equipas, `ATLETA`/`ENCARREGADO` só leitura das sessões da equipa do atleta, `FINANCEIRO` só leitura, gestão total
  para `ADMIN`/`DIRECAO`), isolamento entre clubes, remoção não destrutiva (a sessão é anonimizada e as presenças
  ficam preservadas) e auditoria.
- `MatchFlowTest`: jogos contra PostgreSQL real. Cobre CRUD, resultados por set (upsert, alteração e remoção),
  convocatórias e respostas (atleta responde só à própria; encarregado só lê), validações, autorização por papel
  (`TREINADOR` só nos jogos das suas equipas), isolamento entre clubes, remoção não destrutiva (sets e convocatórias
  preservados), auditoria e a notificação da convocatória por e-mail/outbox (atleta e encarregado, deduplicação e
  atletas sem contacto).
- `DocumentFlowTest`: documentos com validade. Cobre CRUD por atleta, validações, autorização por recurso
  (`TREINADOR` só nos atletas das suas equipas, `ATLETA`/`ENCARREGADO` só no próprio/educandos, `FINANCEIRO` só lê),
  isolamento entre clubes, remoção não destrutiva, auditoria e os alertas de caducidade do `DocumentExpiryNotifier`
  (janela de 30 dias, documentos expirados/anonimizados/sem e-mail ignorados, e deduplicação semanal com reenvio após
  alteração da validade).
- `BillingFlowTest`: financeiro contra PostgreSQL real. Cobre planos de quotas, cobranças, pagamentos e lançamentos
  contabilísticos; validações (`400`/`404`/`409` de nome repetido), autorização (`ADMIN`/`DIRECAO`/`FINANCEIRO`
  escrevem, `TREINADOR` não entra, `ATLETA`/`ENCARREGADO` só vêem as suas cobranças), isolamento entre clubes,
  remoção não destrutiva (pagamentos preservados), auditoria e a geração automática de quotas (cadência mensal,
  trimestral e anual, filtros por escalão, `dryRun`, idempotência e agendador).
- `ExpiredDataPurgerTest`: a limpeza remove convites/tokens expirados, aceites, usados ou revogados de
  **vários clubes** numa só chamada, e mantém os dados recentes/válidos e as mensagens `PENDING` da outbox.

Os testes unitários (`AgeGroupEligibilityTest`, `LoginRateLimiterTest`, `ClientIpResolverTest`,
`ProvisioningServiceTest`, `ExpiredDataPurgerUnitTest`, `TotpTest`, `TenantClaimValidatorTest`,
`AuthServiceUnitTest`, `SecurityConfigTest`)
não precisam de Docker; os dois primeiros controlam o relógio para verificar a expiração do bloqueio sem esperar,
e o `TotpTest` confere os códigos com os vetores do RFC 6238. O `AgeGroupEligibilityTest` fixa a matriz da regra
FPV (faixa exata, atleta mais novo com e sem autorização, atleta mais velho sempre proibido, Seniores e Veteranos
sem limite superior e o cálculo da idade na data de referência).

### Cobertura de testes (JaCoCo)

O build está instrumentado com JaCoCo 0.8.14; `./mvnw test` deixa o relatório em `target/site/jacoco/`:

| Ficheiro     | Uso                                                             |
|--------------|-----------------------------------------------------------------|
| `index.html` | Relatório navegável (packages → classes → linhas e ramos)       |
| `jacoco.xml` | Consumo por CI/SonarQube/Codecov                                |
| `jacoco.csv` | Resumo por classe para scripts                                   |

Resumo no terminal:

```bash
./scripts/coverage.ps1              # corre os testes e resume a cobertura por package
./scripts/coverage.ps1 -Open        # idem e abre o HTML no browser
./scripts/coverage.ps1 -NoTests     # relê o último relatório sem correr testes
```

Impor mínimos (o build falha abaixo deles):

```bash
./mvnw -Pcoverage-check verify
./mvnw -Pcoverage-check verify -Djacoco.min.line=0.92 -Djacoco.min.branch=0.72
```

Os limiares por omissão são **90% de linhas** e **70% de ramos** (`pom.xml`). Fora do perfil a cobertura é
apenas medida e nunca falha o build; os valores podem ser trocados na linha de comandos
(`-Djacoco.min.line=0.92 -Djacoco.min.branch=0.72`). A conta abrange `src/main/java` (exclui só `package-info`).

Na última execução a suite dava **92,9% de linhas** (2940/3165) e **76,9% de ramos** (1032/1342).
As maiores lacunas estão em `shared` (74,8% de linhas) e `billing` (79,7%); os restantes módulos
ficam acima de 90% de linhas.

No CI, cada execução publica o relatório como artefacto `cobertura-jacoco` e escreve o resumo no
*step summary* do workflow.

Integração contínua: workflow GitHub Actions em `.github/workflows/ci.yml` (JDK 25, `mvn -B -ntp
-Pcoverage-check verify`, com Docker disponível no runner). Em `master`, o mesmo workflow publica a
imagem e faz deploy para staging (ver a secção seguinte).

## CI/CD e staging

O workflow `.github/workflows/ci.yml` tem quatro jobs:

| Job             | Quando corre                                    | O que faz                                                                                                                     |
|-----------------|-------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `test`          | todos os pushes, pull requests e execução manual | `mvn -B -ntp -Pcoverage-check verify` com JDK 25 (o Testcontainers usa o Docker do runner) e falha abaixo dos limiares de cobertura |
| `frontend`      | todos os pushes, pull requests e execução manual | em `frontend/`: Node 20 com cache npm, `npm ci` e `npm run build`                                                              |
| `image`         | depois dos testes                               | valida o `Dockerfile` em pull requests; em `master` publica `ghcr.io/<owner>/<repo>:<sha>` e `:staging` no GHCR                |
| `deploy-staging`| só em `master`                                  | depois de `image` e `frontend`; por SSH, atualiza a stack Docker Compose no servidor e espera pelos healthchecks; falha com os logs se a app não ficar saudável |

O deploy de staging está desligado por omissão: o job `deploy-staging` fica *skipped* enquanto não
existir a variável de repositório `STAGING_ENABLED` com o valor `true`. Para o ligar, criar essa
variável em *Settings > Secrets and variables > Actions > Variables* e configurar no environment
`staging` os secrets e variables que o passo de validação exige (tabelas abaixo).

A imagem é multi-stage (`Dockerfile`): build com Maven/JDK 25 e runtime `eclipse-temurin:25-jre-alpine`, com
utilizador sem privilégios e healthcheck em `/api/v1/ping`. A tag `:<sha>` é imutável e é a usada no deploy;
`:staging` aponta sempre para a última versão de `master`.

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

O deploy corre automaticamente em cada push para `master`; também pode ser disparado à mão em
*Actions > CI > Run workflow* (no branch `master`). Para voltar a uma versão anterior, usar a tag imutável
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
| `athletes`                                            | Atletas, encarregados de educação, ligação a contas, documentos com validade, importação CSV e estatísticas, com autorização por recurso |
| `teams`                                               | Equipas, épocas e escalões (CRUD, staff e plantel)                                                                                           |
| `training`                                            | Treinos: locais (pavilhões/campos), sessões, presenças                                                                                       |
| `matches`                                             | Jogos: calendário, resultados por set, convocatórias                                                                                         |
| `billing`                                             | Financeiro: planos de quotas, cobranças, pagamentos e lançamentos contabilísticos                                                             |

Migrações Flyway em `src/main/resources/db/migration`:
`V1` esquema base, `V2` RLS/permissões, `V3` refresh tokens, `V4` convites/reset de password,
`V5` visibilidade de `app_user` por membership, `V6` outbox de e-mail/permissões de provisionamento,
`V7` função `provision_tenant`, `V8` função/índices de limpeza periódica, `V9` atletas (conta, estado e
encarregados) e base mínima de equipas (staff e plantel), `V10` remoção não destrutiva (anonimização e
índices de unicidade parciais), `V11` épocas e escalões (`season`, `age_group` e autorizações de subida
`season_promotion`), com a associação à equipa, `V12` treinos e jogos (`venue`, `training_session`,
`attendance`, `match`, `set_score`, `call_up`), `V13` documentos com validade por atleta e importação CSV,
`V14` financeiro (`fee_plan`, `charge`, `payment`, `ledger_entry`), `V15` deduplicação dos alertas de
documentos (`document.expiry_notified_at`), `V16` geração de cobranças (`charge.billing_period` e índice
único parcial de idempotência), `V17` inscrições oficiais por atleta/época, `V18` modalidades,
datas de corte, épocas automáticas e exceções de quotas, `V19` remoção em cascata das referências
de escalão, `V20` promoções permanentes de atletas, `V21` catálogo FPV de escalões por idade
(substitui o catálogo por época: os escalões passam a ser globais, com `code`, `min_age`/`max_age`
e apenas `GRANT SELECT` para `volei_app`; antes de recriar as chaves externas, a migração remapeia
as referências antigas por nome — equipas, inscrições, promoções e planos de quotas — e apaga as
que não têm correspondência), `V22` promoções associadas a pagamento, `V23` desistência do atleta,
`V24` MFA por TOTP (`app_user.mfa_*` e `mfa_recovery_code`) e `V25` autorizações de escalão
auditáveis (`season_promotion.reason` e índice parcial por época/escalão). Papéis em
`docker/postgres-init/01-roles.sql`, também
usado pelos testes (passwords `dev`, só para desenvolvimento; o `volei_migrator` tem `BYPASSRLS`).

> **Atenção ao checksum do Flyway:** a `V21` foi reescrita depois de já ter corrido em bases de
> desenvolvimento, pelo que o checksum guardado em `flyway_schema_history` deixa de coincidir e o
> arranque falha com *Migration checksum mismatch*. Em desenvolvimento, recriar o volume
> (`docker compose down -v`) e voltar a semear; em bases com dados a conservar (staging), correr
> `flyway repair` — ou aplicar o projeto numa base nova — antes de arrancar a aplicação.

Scripts de apoio em `scripts/`: `coverage.ps1` resume a cobertura JaCoCo por package (`-Open`, `-NoTests`,
`-MinLine`).

## Roadmap

| Fase | Duração | Entregáveis | Critério de conclusão |
| :--- | :--- | :--- | :--- |
| **0. Fundações** | 3 semanas | Repositório, CI/CD, Flyway, Testcontainers, tenancy com RLS, autenticação OIDC, papéis, auditoria, ambiente de staging | Teste automático prova que o tenant A não lê dados do tenant B |
| **1. Atletas e equipas** | 4 semanas | Épocas, escalões, atletas, encarregados, documentos com validade, equipas e plantéis, importação CSV | Clube-piloto carrega o seu plantel real |
| **2. Treinos e jogos** | 5 semanas | Calendário, recorrência, presenças, jogos e resultados, convocatórias com confirmação, notificações e PWA | Um treinador convoca e recebe respostas pelo telemóvel |
| **3. Quotas e financeiro** | 6 semanas | Planos, geração de cobranças, pagamentos Multabanco/MB WAY, webhooks, reconciliação, mapa de dívidas, despesas | Primeiro mês de quotas cobrado e reconciliado sem intervenção manual |
| **4. Piloto e endurecimento** | 3 semanas | RGPD (consentimentos, exportação, anonimização), testes de carga, backups testados, monitorização, manuais | 1 a 2 clubes em produção durante um mês completo |
| **5. Onboarding multi-clube** | 3 semanas | Auto-registo de clubes, configuração de marca, mensalidade única por clube (sem planos/tiers) e faturação da plataforma, backoffice | Novo clube criado e operacional em menos de 1 hora |

**Depois da v1:** integração com a federação, app móvel nativo, estatísticas táticas, loja do clube, comunicação por WhatsApp/SMS, gestão de equipamentos e transportes.

## Por fazer

- Módulo financeiro: backend e frontend entregues, com quotas recorrentes, taxas únicas por atleta,
  geração de cobranças, pagamentos, estados, mapa de pendentes e lançamentos contabilísticos. Falta a integração real com
  Multibanco/MB WAY (webhooks e reconciliação automática).
- Fase 2 (treinos e jogos): backend entregue (sessões, presenças, jogos, sets e convocatórias), com notificação
  das convocatórias por e-mail/outbox; falta a PWA e as notificações push/SMS.
- Alertas de caducidade de documentos: envio diário às 08:00 com janela de 30 dias e deduplicação semanal (V15);
  falta permitir configurar a antecedência por clube.
- Roadmap — fases seguintes: **4. Piloto e endurecimento** (RGPD: consentimentos, exportação e anonimização; testes
  de carga; backups testados; monitorização; manuais) e **5. Onboarding multi-clube** (auto-registo, configuração de
  marca, mensalidade única por clube — sem planos/tiers — e faturação da plataforma, backoffice). Depois da v1:
  integração com a federação, app móvel nativa, estatísticas táticas, loja do clube, comunicação por WhatsApp/SMS,
  gestão de equipamentos e transportes.
