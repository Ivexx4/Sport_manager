# Gestão de Clubes de Voleibol

Monólito modular (Java 25, Spring Boot 4.1.2, Maven) com PostgreSQL e multitenancy por `tenant_id` + Row Level Security.
Estado atual: **fases 0 e 1 concluídas, fase 2 em curso** com os módulos de **atletas** (CRUD, documentos com
validade e importação CSV), **equipas** (com épocas, escalões e regra federativa FPV), **treinos e jogos**
(sessões, presenças, jogos, resultados por set e convocatórias) e **financeiro** (planos de quotas, cobranças,
pagamentos e lançamentos). Inclui ainda estatísticas de assiduidade/convocatórias por atleta e alertas de
caducidade de documentos por e-mail:
tenancy com RLS, papéis da base de dados, filtro de tenant, testes de isolamento, autenticação por JWT próprio,
limitação de tentativas de login, convites/utilizadores, recuperação de password, provisionamento de clubes,
e-mail por outbox (com tentativas), limpeza periódica de dados expirados, CI e CD para staging; atletas,
encarregados de educação, equipas, épocas e escalões com autorização por recurso.

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
(equipa, staff e plantel); o módulo `teams` completo (equipas, staff, plantel, épocas, escalões e regra federativa
FPV) foi entregue.

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
| `POST /api/v1/teams`                          | `ADMIN`, `DIRECAO`                 | `{name, status?, seasonId?, ageGroupId?}`; nome único por clube (sem distinguir maiúsculas); época/escalão têm de existir no clube (`404`) |
| `PUT /api/v1/teams/{id}`                      | `ADMIN`, `DIRECAO`                 | Atualiza nome/estado e associações; `null` em `seasonId`/`ageGroupId` remove a associação |
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

No `POST /api/v1/teams/{teamId}/athletes` (`addAthleteToTeam`) o plantel passa a validar a idade do
atleta contra o escalão da época da equipa (`season` → `age_group`):

1. O atleta tem de estar no mesmo clube e não estar já no plantel (`404`/`409`).
2. A equipa tem de estar associada a uma época e a um escalão (`400` se não o estiver).
3. Com o `birth_year` do atleta (extraído de `birth_date`), a regra federativa é:
   - `min_birth_year <= birth_year <= max_birth_year` → **permite** a inserção;
   - `birth_year > max_birth_year` (subida) → exige **autorização excepcional** de subida de escalão
     para a época (`season_promotion`, `ACTIVE`); sem autorização → `400`;
   - `birth_year < min_birth_year` (tentativa de jogar em escalão inferior) → **`400`**:
     "Proibido atuar em escalão inferior conforme regulamento FPV".

### Endpoints de épocas e escalões

| Endpoint                            | Quem pode          | Descrição                                                                 |
|-------------------------------------|--------------------|---------------------------------------------------------------------------|
| `GET /api/v1/seasons`               | qualquer membro    | Lista as épocas do clube                                                  |
| `GET /api/v1/seasons/{id}`          | qualquer membro    | Detalhe; `404` se não existir no clube                                    |
| `POST /api/v1/seasons`              | `ADMIN`, `DIRECAO` | `{name, startDate?, endDate?, isActive?}` → 201                           |
| `PUT /api/v1/seasons/{id}`          | `ADMIN`, `DIRECAO` | Atualiza a época; nome único por clube                                    |
| `DELETE /api/v1/seasons/{id}`       | `ADMIN`, `DIRECAO` | Anonimiza a época (`isActive = false` + `anonymized_at`); as equipas associadas mantêm-se |
| `GET /api/v1/age-groups`            | qualquer membro    | Lista os escalões (por época) do clube                                   |
| `GET /api/v1/age-groups/{id}`       | qualquer membro    | Detalhe; `404` se não existir no clube                                    |
| `POST /api/v1/age-groups`           | `ADMIN`, `DIRECAO` | `{seasonId, name, minBirthYear, maxBirthYear}` → 201                     |
| `PUT /api/v1/age-groups/{id}`       | `ADMIN`, `DIRECAO` | Atualiza o escalão; nome único na época                                   |
| `DELETE /api/v1/age-groups/{id}`    | `ADMIN`, `DIRECAO` | Anonimiza o escalão; o registo e as equipas associadas mantêm-se          |

`isActive` marca a época corrente; `startDate`/`endDate` são opcionais, mas a data de fim não pode ser anterior à de início. Os escalões são faixas de anos de nascimento (`minBirthYear`/`maxBirthYear`) de uma época (`seasonId` obrigatório). As épocas e os escalões anonimizados deixam de aparecer nas leituras e não podem ser associados a novas equipas.

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

Exceção: os escalões (`age_group`) passaram a pertencer a uma época (`season`) e a conter anos de nascimento;
o `DELETE` mantém a convenção de remoção não destrutiva (anonimização), preservando o registo e as
equipas associadas.

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
convocatória (só o atleta ou a gestão/treinador o podem fazer).

## Financeiro (fase 3)

| Endpoint                                      | Quem pode                                      | Descrição                                                                 |
|-----------------------------------------------|------------------------------------------------|---------------------------------------------------------------------------|
| `GET /api/v1/fee-plans`                       | `ADMIN`, `DIRECAO`, `FINANCEIRO`               | Lista os planos de quotas                                                 |
| `POST /api/v1/fee-plans`                      | idem                                           | `{name, amountCents, seasonId?, ageGroupId?, periodicity?}` → 201; nome único (`409`) |
| `DELETE /api/v1/fee-plans/{id}`               | idem                                           | Anonimiza o plano                                                        |
| `GET /api/v1/charges`                         | gestão/`FINANCEIRO` (tudo) e `ATLETA`/`ENCARREGADO` (âmbito) | Lista; filtros `athleteId` e `status`                                    |
| `POST /api/v1/charges`                        | `ADMIN`, `DIRECAO`, `FINANCEIRO`               | `{athleteId, amountCents, dueDate, guardianId?, feePlanId?, status?}` → 201 |
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

## Modelo de dados

Todas as tabelas abaixo levam `tenant_id`, `id` (UUID), `created_at`, `updated_at` e `created_by`; as que não referem tenant (`tenant`, `app_user`) são geridas pela plataforma.

| Entidade | Campos principais | Relações |
| :--- | :--- | :--- |
| `tenant` | nome, slug, NIF, estado, plano | raiz de todos os dados |
| `app_user` | email, nome, estado | N:N com tenant via membership |
| `membership` | user_id, papel, estado | liga utilizador a clube com papel |
| `season` | nome, início, fim, ativa | 1:N com team |
| `age_group` | nome, idade mín./máx., regra de data de corte | 1:N com team |
| `team` | nome, season_id, age_group_id, género | 1:N com roster_entry, training_session, match |
| `athlete` | nome, data de nascimento, género, NIF, contactos, nº licença, estado, conta | N:N com `athlete_guardian`; 1:N com document, attendance e call_up |
| `athlete_guardian` | athlete_id, user_id, parentesco | liga atleta a encarregado de educação |
| `team` | nome, season_id, age_group_id, género | 1:N com `team_athlete`, `team_staff`, training_session e match |
| `team_athlete` | team_id, athlete_id | plantel da equipa |
| `team_staff` | team_id, user_id, função | treinador, delegado |
| `document` | athlete_id, tipo, ficheiro, validade, `expiry_notified_at` | alerta de caducidade (com deduplicação semanal) |
| `venue` | nome, morada | pavilhão/campo usado por treinos e jogos |
| `training_session` | team_id, court_id, início, fim, recorrência, estado | 1:N com attendance |
| `attendance` | session_id, athlete_id, estado, justificação | |
| `match` | team_id, competição, adversário, local, court_id, data/hora, casa/fora, estado | 1:N com set_score e call_up |
| `set_score` | match_id, nº do set, pontos nossos, pontos adversário | |
| `call_up` | match_id, athlete_id, estado, resposta, data da resposta | convocatória |
| `fee_plan` | nome, season_id, age_group_id, valor, periodicidade | gera charges |
| `charge` | athlete_id, guardian_id, fee_plan_id, valor, vencimento, estado | 1:N com payment |
| `payment` | charge_id, método, valor, referência externa, data | reconciliação do agregador |
| `ledger_entry` | tipo, categoria, valor, data, descrição | receitas e despesas do clube |
| `audit_log` | utilizador, ação, entidade, antes/depois (JSONB), data | só de escrita |

### Regras de modelação:
- Valores monetários em inteiros (cêntimos) ou `numeric(12,2)`, nunca `float`.
- Datas e horas em `timestamptz`, guardadas em UTC e mostradas no fuso do clube (`Europe/Lisbon`).
- Um atleta pode ter várias épocas; o escalão é sempre derivado da época e da data de nascimento, não guardado no atleta.
- Apagar atletas faz-se por anonimização (RGPD), mantendo os registos financeiros exigidos por lei.

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
  remoção não destrutiva (a equipa é anonimizada e staff/plantel ficam preservados), o âmbito do `TREINADOR`
  (só vê as equipas em que é staff e não as gere) e a regra federativa FPV no plantel (faixa de escalão, subida com
  autorização excepcional e proibição em escalão inferior).
- `SeasonAgeGroupFlowTest`: CRUD de épocas e escalões, validações de datas/anos de nascimento, leitura aberta a qualquer
  membro, escrita só para `ADMIN`/`DIRECAO`, isolamento entre clubes, remoção não destrutiva e associação de
  `seasonId`/`ageGroupId` às equipas (incluindo referências de outro clube ou anonimizadas).
- `TrainingFlowTest`: locais e sessões de treino contra PostgreSQL real. Cobre CRUD, nome de local único (`409`),
  validações (`400`/`404`), presenças (upsert, listagem e remoção), autorização por papel (`TREINADOR` só nas suas
  equipas, `ATLETA`/`ENCARREGADO` só leitura das sessões da equipa do atleta, `FINANCEIRO` só leitura, gestão total
  para `ADMIN`/`DIRECAO`), isolamento entre clubes, remoção não destrutiva (a sessão é anonimizada e as presenças
  ficam preservadas) e auditoria.
- `MatchFlowTest`: jogos contra PostgreSQL real. Cobre CRUD, resultados por set (upsert, alteração e remoção),
  convocatórias e respostas (atleta responde só à própria; encarregado só lê), validações, autorização por papel
  (`TREINADOR` só nos jogos das suas equipas), isolamento entre clubes, remoção não destrutiva (sets e convocatórias
  preservados) e auditoria.
- `DocumentFlowTest`: documentos com validade. Cobre CRUD por atleta, validações, autorização por recurso
  (`TREINADOR` só nos atletas das suas equipas, `ATLETA`/`ENCARREGADO` só no próprio/educandos, `FINANCEIRO` só lê),
  isolamento entre clubes, remoção não destrutiva, auditoria e os alertas de caducidade do `DocumentExpiryNotifier`
  (janela de 30 dias, documentos expirados/anonimizados/sem e-mail ignorados, e deduplicação semanal com reenvio após
  alteração da validade).
- `BillingFlowTest`: financeiro contra PostgreSQL real. Cobre planos de quotas, cobranças, pagamentos e lançamentos
  contabilísticos; validações (`400`/`404`/`409` de nome repetido), autorização (`ADMIN`/`DIRECAO`/`FINANCEIRO`
  escrevem, `TREINADOR` não entra, `ATLETA`/`ENCARREGADO` só vêem as suas cobranças), isolamento entre clubes,
  remoção não destrutiva (pagamentos preservados) e auditoria.
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
`V14` financeiro (`fee_plan`, `charge`, `payment`, `ledger_entry`) e `V15` deduplicação dos alertas de
documentos (`document.expiry_notified_at`). Papéis em
`docker/postgres-init/01-roles.sql`, também
usado pelos testes (passwords `dev`, só para desenvolvimento; o `volei_migrator` tem `BYPASSRLS`).

## Roadmap

| Fase | Duração | Entregáveis | Critério de conclusão |
| :--- | :--- | :--- | :--- |
| **0. Fundações** | 3 semanas | Repositório, CI/CD, Flyway, Testcontainers, tenancy com RLS, autenticação OIDC, papéis, auditoria, ambiente de staging | Teste automático prova que o tenant A não lê dados do tenant B |
| **1. Atletas e equipas** | 4 semanas | Épocas, escalões, atletas, encarregados, documentos com validade, equipas e plantéis, importação CSV | Clube-piloto carrega o seu plantel real |
| **2. Treinos e jogos** | 5 semanas | Calendário, recorrência, presenças, jogos e resultados, convocatórias com confirmação, notificações e PWA | Um treinador convoca e recebe respostas pelo telemóvel |
| **3. Quotas e financeiro** | 6 semanas | Planos, geração de cobranças, pagamentos Multabanco/MB WAY, webhooks, reconciliação, mapa de dívidas, despesas | Primeiro mês de quotas cobrado e reconciliado sem intervenção manual |
| **4. Piloto e endurecimento** | 3 semanas | RGPD (consentimentos, exportação, anonimização), testes de carga, backups testados, monitorização, manuais | 1 a 2 clubes em produção durante um mês completo |
| **5. Onboarding multi-clube** | 3 semanas | Auto-registo de clubes, configuração de marca, planos SaaS e faturação da plataforma, backoffice | Novo clube criado e operacional em menos de 1 hora |

**Depois da v1:** integração com a federação, app móvel nativo, estatísticas táticas, loja do clube, comunicação por WhatsApp/SMS, gestão de equipamentos e transportes.

## Por fazer

- MFA para administradores (fase 0).
- Módulo financeiro (V14): planos de quotas, cobranças, pagamentos e lançamentos entregues, com autorização por
  recurso (o `FINANCEIRO` gere tudo e o `ATLETA`/`ENCARREGADO` só vêem as cobranças que lhes pertencem). Falta a
  integração real com Multibanco/MB WAY (webhooks e reconciliação automática) e o mapa de dívidas.
- Fase 2 (treinos e jogos): backend entregue (sessões, presenças, jogos, sets e convocatórias); falta a PWA e as
  notificações push/SMS das convocatórias.
- Alertas de caducidade de documentos: envio diário às 08:00 com janela de 30 dias e deduplicação semanal (V15);
  falta permitir configurar a antecedência por clube.
- Roadmap — fases seguintes: **4. Piloto e endurecimento** (RGPD: consentimentos, exportação e anonimização; testes
  de carga; backups testados; monitorização; manuais) e **5. Onboarding multi-clube** (auto-registo, configuração de
  marca, planos SaaS e faturação da plataforma, backoffice). Depois da v1: integração com a federação, app móvel
  nativa, estatísticas táticas, loja do clube, comunicação por WhatsApp/SMS, gestão de equipamentos e transportes.
