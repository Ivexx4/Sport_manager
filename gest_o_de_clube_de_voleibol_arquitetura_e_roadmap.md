# Gestão de Clube de Voleibol: arquitetura e roadmap

**Nov 10, 2026** | **@Ivo Rosa**

Plataforma SaaS multitenant em Java e PostgreSQL, com base de dados partilhada, `tenant_id` em todas as tabelas e Row Level Security, para que cada clube tenha os seus dados isolados. A versão 1 cobre atletas e escalões, treinos, jogos e convocatórias, e quotas.

---

## Stack e arquitetura

Começar com um monólito modular: uma só aplicação deployável, com módulos separados por domínio. É mais simples de operar e de testar do que microserviços, e pode ser dividido mais tarde se for preciso.

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
* **Requisitos da documentação oficial:** Java 17 a 27 (Java 25 suportado), Spring Framework 7.0.10 ou superior, Maven 3.6.3+ ou Gradle 8.14+/9.x, Tomcat 11 embebido (Servlet 6.1).
* **Rutura do Spring Boot 4.0:** O Spring Boot 4.0 foi uma rutura face ao 3.x: base Jakarta EE 11, Jackson 3, starters modulares (JARs de auto-configuração divididos) e null safety com JSpecify. Vê o resumo do 4.1 na InfoQ e o guia de migração oficial antes de copiar exemplos escritos para o 3.x.
* **Geração de código:** Muito material online (e muito código gerado por IA) ainda assume Spring Boot 3: ao pedir código, indicar sempre a versão exata e validar as dependências e os nomes dos starters contra a documentação do 4.1.
* **Segurança nas migrações:** Os testes de integração com Testcontainers e RLS continuam a ser a rede de segurança para migrações de versão.

---

## Multitenancy com `tenant_id` e RLS

Cada clube é um tenant. Todas as tabelas de negócio têm `tenant_id` e o PostgreSQL recusa, por política, qualquer linha de outro tenant, mesmo que o código da aplicação tenha um erro.

1. **Resolução do tenant:** por subdomínio (`clube-x.app.pt`) e confirmada contra o claim `tenant_id` do JWT. O pedido nunca escolhe o tenant sozinho.
2. **Contexto por transação:** um filtro Spring guarda o `tenant_id` num `TenantContext` e, no início de cada transação, executa `SELECT set_config('app.tenant_id', id, true)`. O `true` limita o valor à transação, o que é obrigatório com pool de ligações.
3. **Políticas RLS:** em cada tabela, `ENABLE` e `FORCE ROW LEVEL SECURITY`, com política `USING (tenant_id = current_setting('app.tenant_id')::uuid)` e a mesma condição em `WITH CHECK`.
4. **Papéis na base de dados:** a aplicação liga-se com um papel sem `BYPASSRLS` e que não é dono das tabelas. Migrações correm com um papel distinto.
5. **Integridade entre tenants:** chaves estrangeiras compostas `(tenant_id, id)` impedem que um registo de um clube aponte para outro.
6. **Índices:** `tenant_id` como primeira coluna nos índices compostos.
7. **Rotinas globais:** relatórios da plataforma e faturação SaaS usam um papel de serviço separado, auditado e fora do caminho dos pedidos normais.

```sql
ALTER TABLE athlete ENABLE ROW LEVEL SECURITY;
ALTER TABLE athlete FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON athlete
USING (tenant_id = current_setting('app.tenant_id')::uuid)
WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);
```

```java
// Executado no início de cada @Transactional (ex.: via aspeto ou TransactionSynchronization)
jdbcTemplate.queryForObject(
    "SELECT set_config('app.tenant_id', ?, true)", String.class,
    tenantId.toString());
```

> Se um dia houver clientes que exijam isolamento físico, o mesmo código serve: esse tenant passa a ter a sua própria base e o `tenant_id` mantém-se.

---

## Módulos da versão 1

### Atletas, escalões e equipas
* **Ficha do atleta:** dados pessoais, contactos, encarregados de educação (atletas menores), foto, documentos (cartão de cidadão, exame médico) com data de validade e alertas de caducidade.
* **Épocas desportivas:** escalões configuráveis por clube (sub-12, sub-14, ... seniores) com regras de idade por época.
* **Equipas:** por época e escalão, com plantel, números de camisola, posições e staff (treinadores, delegados).
* **Registo federativo:** número de licença e estado da inscrição (a integração com a Federação Portuguesa de Voleibol fica para fase posterior).

### Treinos, jogos e convocatórias
* **Calendário de treinos:** recorrentes por equipa, com local (pavilhão e campo) e deteção de conflitos de espaço.
* **Presenças nos treinos:** com justificação de faltas.
* **Jogos:** competição, adversário, local, hora, casa/fora, resultado por set e relatório simples.
* **Convocatórias:** o treinador convoca, o atleta ou encarregado confirma ou recusa, com lembretes automáticos (e-mail e push).
* **Estatísticas:** básicas por atleta (jogos, sets, presenças); estatísticas táticas por ação ficam fora da v1.

### Quotas e financeiro
* **Planos de quotas:** por escalão e época (inscrição, mensalidade, material), com descontos (irmãos, bolsas).
* **Cobranças:** geração mensal automática por atleta ou por encarregado de educação.
* **Pagamentos:** referência Multibanco, MB WAY ou numerário, com reconciliação automática via webhook do agregador.
* **Gestão de dívidas:** estados da cobrança (pendente, paga, em atraso, cancelada), lembretes e mapa de dívidas.
* **Contabilidade e faturação:** despesas e receitas simples do clube e relatório mensal. Emissão de fatura certificada pela AT fica por integração com software de faturação externo (ex.: Moloni, InvoiceXpress) em vez de a construir.

### Transversal
* **Gestão de utilizadores e papéis por clube:** administrador, direção, financeiro, treinador, atleta, encarregado de educação.
* **Funcionalidades gerais:** notificações (e-mail, push PWA), exportação CSV/PDF, registo de auditoria.

---

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
| `athlete` | nome, data de nascimento, género, NIF, contactos, nº licença | N:N com guardian; 1:N com document |
| `guardian` | nome, contactos, NIF de faturação | N:N com athlete |
| `roster_entry` | team_id, athlete_id, nº camisola, posição, estado | liga atleta a equipa numa época |
| `staff_assignment` | team_id, membership_id, função | treinador, delegado |
| `document` | athlete_id, tipo, ficheiro, validade | alerta de caducidade |
| `venue / court` | nome, morada / pavilhão, campo | usados por treinos e jogos |
| `training_session` | team_id, court_id, início, fim, recorrência | 1:N com attendance |
| `attendance` | session_id, athlete_id, estado, justificação | |
| `match` | team_id, competição, adversário, local, data/hora, casa/fora, resultado | 1:N com set_score e call_up |
| `set_score` | match_id, nº do set, pontos nossos, pontos adversário | |
| `call_up` | match_id, athlete_id, estado, resposta, data da resposta | convocatória |
| `fee_plan` | nome, season_id, age_group_id, valor, periodicidade | gera charges |
| `charge` | athlete_id, guardian_id, fee_plan_id, valor, vencimento, estado | 1:N com payment |
| `payment` | charge_id, método, valor, referência externa, data | reconciliação do agregador |
| `ledger_entry` | tipo, categoria, valor, data, descrição | receitas e despesas do clube |
| `audit_log` | utilizador, ação, entidade, antes/depois (JSONB), data | só de escrita |

### Regras de modelação:
* Valores monetários em inteiros (cêntimos) ou `numeric(12,2)`, nunca `float`.
* Datas e horas em `timestamptz`, guardadas em UTC e mostradas no fuso do clube (`Europe/Lisbon`).
* Um atleta pode ter várias épocas; o escalão é sempre derivado da época e da data de nascimento, não guardado no atleta.
* Apagar atletas faz-se por anonimização (RGPD), mantendo os registos financeiros exigidos por lei.

---

## Segurança, RGPD e auditoria

O clube trata dados de menores, incluindo saúde (exames médicos) e imagem, pelo que a proteção de dados é requisito desde o primeiro dia.

* **Autorização:** papéis por clube (RBAC) e regras ao nível do recurso. Exemplo: um encarregado de educação só vê os atletas a que está associado; um treinador só vê as suas equipas.
* **Dados sensíveis:** documentos médicos em armazenamento de objetos (S3 compatível) com cifragem, acesso por URL assinado e curta duração; campos como NIF e telefone cifrados em repouso.
* **RGPD:** registo de consentimentos (tratamento, imagem, comunicações), exportação dos dados do titular, anonimização a pedido, política de retenção por tipo de dado e Acordo de Subcontratação (DPA) com cada clube, já que a plataforma é subcontratante.
* **Auditoria:** `audit_log` imutável para ações sobre dados pessoais e financeiros; acesso do suporte da plataforma ao tenant só com autorização e registo.
* **Aplicação:** HTTPS obrigatório, cabeçalhos de segurança, limitação de pedidos por tenant e IP, validação de entrada, dependências analisadas (OWASP Dependency-Check) e MFA para administradores.
* **Cópias de segurança:** backups diários com restauro testado, e teste automático que garante que nenhum tenant lê dados de outro (suite de testes de isolamento com Testcontainers a correr em cada build).
* **Alojamento:** região da UE (idealmente Portugal ou Europa Ocidental).

---

## Roadmap por fases

A versão 1 demora cerca de **9 a 10 meses** a solo, com apoio de IA e a tempo inteiro, ou o dobro a tempo parcial (estimativa grosseira, a rever após a fase 0). As durações da tabela abaixo assumem 2 programadores: multiplica por 1,6 a 1,8. Cada fase termina com algo que um clube-piloto consegue usar.

### Ajustes para desenvolver sozinho:
* Autenticação com Spring Security + JWT próprio na v1, em vez de Keycloak: menos uma peça para operar. Migrar para OIDC externo mais tarde, se for preciso.
* Tarefas assíncronas com uma tabela outbox e `@Scheduled` em vez de RabbitMQ.
* Um único servidor (VPS na UE, com Docker Compose) e PostgreSQL gerido ou com backups automáticos; PgBouncer só quando houver carga que o justifique.
* Usar a IA para o código repetitivo (CRUDs, DTOs, migrações, testes), mas rever à mão tudo o que toca em RLS, autorização e pagamentos.
* Escrever primeiro os testes de isolamento entre tenants: protegem-te de erros que a IA possa introduzir.
* O clube do teu irmão é a fonte de requisitos: recolher já os seus ficheiros reais (Excel de atletas, mapa de quotas, calendário) para desenhar a importação da fase 1.

| Fase | Duração | Entregáveis | Critério de conclusão |
| :--- | :--- | :--- | :--- |
| **0. Fundações** | 3 semanas | Repositório, CI/CD, Flyway, Testcontainers, tenancy com RLS, autenticação OIDC, papéis, auditoria, ambiente de staging | Teste automático prova que o tenant A não lê dados do tenant B |
| **1. Atletas e equipas** | 4 semanas | Épocas, escalões, atletas, encarregados, documentos com validade, equipas e plantéis, importação CSV | Clube-piloto carrega o seu plantel real |
| **2. Treinos e jogos** | 5 semanas | Calendário, recorrência, presenças, jogos e resultados, convocatórias com confirmação, notificações e PWA | Um treinador convoca e recebe respostas pelo telemóvel |
| **3. Quotas e financeiro** | 6 semanas | Planos, geração de cobranças, pagamentos Multibanco/MB WAY, webhooks, reconciliação, mapa de dívidas, despesas | Primeiro mês de quotas cobrado e reconciliado sem intervenção manual |
| **4. Piloto e endurecimento** | 3 semanas | RGPD (consentimentos, exportação, anonimização), testes de carga, backups testados, monitorização, manuais | 1 a 2 clubes em produção durante um mês completo |
| **5. Onboarding multi-clube** | 3 semanas | Auto-registo de clubes, configuração de marca, planos SaaS e faturação da plataforma, backoffice | Novo clube criado e operacional em menos de 1 hora |

**Depois da v1:** integração com a federação, app móvel nativa, estatísticas táticas, loja do clube, comunicação por WhatsApp/SMS, gestão de equipamentos e transportes.

---

## Riscos e decisões em aberto

| Risco | Impacto | Mitigação |
| :--- | :--- | :--- |
| **RLS mal configurada expõe dados entre clubes** | Crítico | Papel sem `BYPASSRLS`, `FORCE RLS`, testes de isolamento em cada build |
| **Pool de ligações mantém `app.tenant_id` de um pedido anterior** | Crítico | `set_config(..., true)` por transação; teste dedicado; cuidado com PgBouncer em modo transação |
| **Âmbito da v1 a crescer** | Alto | Congelar a lista de módulos; tudo o resto vai para "Depois da v1" |
| **Reconciliação de pagamentos e requisitos fiscais** | Alto | Usar agregador com webhooks e software de faturação certificado, sem construir faturação própria |
| **Dados de menores e de saúde** | Alto | Cifragem, consentimentos, DPA e revisão por jurista antes do primeiro clube real |

### Decisões tomadas:
* Desenvolvimento a solo, com apoio de IA.
* Clube-piloto: o clube do teu irmão.
* Frontend em React (PWA).

### Ainda em aberto:
- [ ] Quanto tempo semanal tens para o projeto? Isto fixa as datas reais do roadmap.
- [ ] Como funciona hoje o clube-piloto: quantos atletas, escalões e equipas, como cobra as quotas e que ferramentas usa?
- [ ] Experiência prévia: já trabalhaste com Spring Boot e PostgreSQL?
- [ ] Alojamento: Hetzner/VPS ou cloud gerida (AWS/Azure)?
- [ ] Agregador de pagamentos preferido e software de faturação do clube.
- [ ] Modelo comercial: preço por clube, por atleta ou por escalão (afeta a fase 5).