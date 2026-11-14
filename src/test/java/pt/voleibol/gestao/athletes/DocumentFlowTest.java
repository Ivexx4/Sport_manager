package pt.voleibol.gestao.athletes;

import com.jayway.jsonpath.JsonPath;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import pt.voleibol.gestao.tenancy.TenantFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documentos com validade contra PostgreSQL real: CRUD, validação, autorização por recurso
 * (gestão, treinador da equipa, atleta/encarregado e financeiro só leitura), isolamento entre
 * clubes, remoção não destrutiva, alertas de caducidade e respetiva deduplicação.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
class DocumentFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000d601");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000d602");

    static final String HOST_A = "clube-docs.localhost";
    static final String HOST_B = "clube-docs-b.localhost";
    static final String PASS = "palavra-passe-teste";

    static final String ADMIN = "admin@docs.test";
    static final String DIRECAO = "direcao@docs.test";
    static final String TREINADOR = "treinador@docs.test";
    static final String FINANCEIRO = "financeiro@docs.test";
    static final String ATLETA_USER = "atleta@docs.test";
    static final String ENCARREGADO = "encarregado@docs.test";
    static final String ADMIN_B = "admin@docs-b.test";

    static final UUID EQUIPA_A = UUID.fromString("00000000-0000-0000-0000-00000000e601");
    static final UUID EQUIPA_OUTRA = UUID.fromString("00000000-0000-0000-0000-00000000e602");
    static final UUID EQUIPA_B = UUID.fromString("00000000-0000-0000-0000-00000000e603");

    static final UUID ATLETA_1 = UUID.fromString("00000000-0000-0000-0000-00000000f601");
    static final UUID ATLETA_2 = UUID.fromString("00000000-0000-0000-0000-00000000f602");
    static final UUID ATLETA_B = UUID.fromString("00000000-0000-0000-0000-00000000f603");

    static final UUID DOC_A = UUID.fromString("00000000-0000-0000-0000-00000000ba01");
    static final UUID DOC_SEM_EMAIL = UUID.fromString("00000000-0000-0000-0000-00000000ba02");
    static final UUID DOC_B = UUID.fromString("00000000-0000-0000-0000-00000000ba03");
    static final UUID DOC_EXPIRADO = UUID.fromString("00000000-0000-0000-0000-00000000ba04");
    static final UUID DOC_LONGE = UUID.fromString("00000000-0000-0000-0000-00000000ba05");
    static final UUID DOC_ANONIMIZADO = UUID.fromString("00000000-0000-0000-0000-00000000ba06");

    static final String EMAIL_ATLETA = "atleta1@docs.test";
    static final String EMAIL_ATLETA_B = "atleta-b@docs.test";

    private static String passwordHash;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "volei_app");
        registry.add("spring.datasource.password", () -> "dev");
        registry.add("spring.flyway.user", () -> "volei_migrator");
        registry.add("spring.flyway.password", () -> "dev");
        registry.add("app.security.jwt.secret", () -> Base64.getEncoder().encodeToString(
                "clave-de-teste-com-mais-de-32-bytes-ok!".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    TenantFilter tenantFilter;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    DocumentExpiryNotifier notifier;

    MockMvc mvc;

    @BeforeEach
    void preparar() throws SQLException {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(tenantFilter)
                .apply(springSecurity())
                .build();
        semear();
    }

    // --- fluxo principal ---

    @Test
    void documentosFluxoCompleto() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criado = criarDocumento(HOST_A, admin, ATLETA_1,
                documento("Cartão de Cidadão", "cc.pdf", "https://files.test/cc.pdf", "2030-01-31"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criado, "$.id"));
        assertThat((String) JsonPath.read(criado, "$.type")).isEqualTo("Cartão de Cidadão");
        assertThat((String) JsonPath.read(criado, "$.athleteId")).isEqualTo(ATLETA_1.toString());
        assertThat((String) JsonPath.read(criado, "$.validUntil")).isEqualTo("2030-01-31");

        String lista = mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/documents")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lista)).contains(DOC_A, id);

        String atualizado = mvc.perform(put("/api/v1/documents/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documento("Cartão de Cidadão Renovado", "cc-novo.pdf",
                                "https://files.test/cc-novo.pdf", "2031-01-31")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(atualizado, "$.type")).isEqualTo("Cartão de Cidadão Renovado");
        assertThat((String) JsonPath.read(atualizado, "$.fileName")).isEqualTo("cc-novo.pdf");

        mvc.perform(delete("/api/v1/documents/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/documents/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        String depois = mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/documents")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(depois)).contains(DOC_A).doesNotContain(id);

        assertThat(contarAuditoria("ATHLETE_DOCUMENT_CREATED")).isPositive();
        assertThat(contarAuditoria("ATHLETE_DOCUMENT_UPDATED")).isPositive();
        assertThat(contarAuditoria("ATHLETE_DOCUMENT_ANONYMIZED")).isPositive();
    }

    @Test
    void remocaoDeDocumentoPreservaRegisto() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        mvc.perform(delete("/api/v1/documents/" + DOC_A)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());

        // A linha mantém-se para histórico/auditoria, apenas anonimizada.
        assertThat(contar("SELECT count(*) FROM document WHERE id = ? AND anonymized_at IS NOT NULL",
                DOC_A)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM document WHERE id = ? AND anonymized_at IS NULL",
                DOC_A)).isZero();
        String lista = mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/documents")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lista)).doesNotContain(DOC_A);
    }

    // --- validação ---

    @Test
    void validacoesDeDocumentos() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        criarDocumento(HOST_A, admin, ATLETA_1, "{\"type\":\" \"}")
                .andExpect(status().isBadRequest());
        criarDocumento(HOST_A, admin, ATLETA_1, "{}")
                .andExpect(status().isBadRequest());
        criarDocumento(HOST_A, admin, UUID.randomUUID(), documento("Exame", null, null, null))
                .andExpect(status().isNotFound());
        criarDocumento(HOST_A, admin, ATLETA_B, documento("Exame", null, null, null))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/athletes/" + UUID.randomUUID() + "/documents")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/athletes/" + ATLETA_B + "/documents")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/v1/documents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documento("Exame", null, null, null)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/documents/" + DOC_A)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/documents/" + DOC_B)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documento("Exame", null, null, null)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/" + DOC_B)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
    }

    // --- autorização por recurso ---

    @Test
    void autorizacaoPorPapelEmDocumentos() throws Exception {
        String direcao = acesso(HOST_A, DIRECAO);
        criarDocumento(HOST_A, direcao, ATLETA_1, documento("Exame da Direção", null, null, null))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/athletes/" + ATLETA_2 + "/documents")
                        .header("Authorization", "Bearer " + direcao).with(host(HOST_A)))
                .andExpect(status().isOk());

        // TREINADOR só nos atletas das equipas em que é staff.
        String treinador = acesso(HOST_A, TREINADOR);
        mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/documents")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isOk());
        criarDocumento(HOST_A, treinador, ATLETA_1, documento("Exame do Treinador", null, null, null))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/athletes/" + ATLETA_2 + "/documents")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        criarDocumento(HOST_A, treinador, ATLETA_2, documento("Exame", null, null, null))
                .andExpect(status().isNotFound());

        // ATLETA só no próprio registo.
        String atleta = acesso(HOST_A, ATLETA_USER);
        String listaAtleta = mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/documents")
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(listaAtleta)).contains(DOC_A);
        mvc.perform(get("/api/v1/athletes/" + ATLETA_2 + "/documents")
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        criarDocumento(HOST_A, atleta, ATLETA_2, documento("Exame", null, null, null))
                .andExpect(status().isNotFound());
        String doAtleta = criarDocumento(HOST_A, atleta, ATLETA_1,
                documento("Atestado do Atleta", null, null, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID docAtleta = UUID.fromString(JsonPath.read(doAtleta, "$.id"));
        mvc.perform(put("/api/v1/documents/" + docAtleta)
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documento("Atestado Renovado", null, null, null)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/documents/" + docAtleta)
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isNoContent());

        // ENCARREGADO só nos educandos.
        String encarregado = acesso(HOST_A, ENCARREGADO);
        mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/documents")
                        .header("Authorization", "Bearer " + encarregado).with(host(HOST_A)))
                .andExpect(status().isOk());
        criarDocumento(HOST_A, encarregado, ATLETA_1, documento("Exame do Educando", null, null, null))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/athletes/" + ATLETA_2 + "/documents")
                        .header("Authorization", "Bearer " + encarregado).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        // FINANCEIRO lê, mas não escreve.
        String financeiro = acesso(HOST_A, FINANCEIRO);
        mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/documents")
                        .header("Authorization", "Bearer " + financeiro).with(host(HOST_A)))
                .andExpect(status().isOk());
        criarDocumento(HOST_A, financeiro, ATLETA_1, documento("Sem Permissão", null, null, null))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/documents/" + DOC_A)
                        .header("Authorization", "Bearer " + financeiro).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documento("Sem Permissão", null, null, null)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/documents/" + DOC_A)
                        .header("Authorization", "Bearer " + financeiro).with(host(HOST_A)))
                .andExpect(status().isForbidden());
    }

    // --- isolamento entre clubes ---

    @Test
    void isolamentoEntreClubes() throws Exception {
        String adminA = acesso(HOST_A, ADMIN);
        String adminB = acesso(HOST_B, ADMIN_B);

        mvc.perform(get("/api/v1/athletes/" + ATLETA_B + "/documents")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        criarDocumento(HOST_A, adminA, ATLETA_B, documento("Exame", null, null, null))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/documents/" + DOC_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documento("Exame", null, null, null)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/" + DOC_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/documents")
                        .header("Authorization", "Bearer " + adminB).with(host(HOST_B)))
                .andExpect(status().isNotFound());
        String listaB = mvc.perform(get("/api/v1/athletes/" + ATLETA_B + "/documents")
                        .header("Authorization", "Bearer " + adminB).with(host(HOST_B)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(listaB)).containsExactly(DOC_B);
    }

    @Test
    void pedidoSemAutenticacao() throws Exception {
        mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/documents").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/documents/" + DOC_A).with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
    }

    // --- alertas de caducidade ---

    @Test
    void alertasDeCaducidadeSaoDeduplicados() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        notifier.notifyExpiring();

        // Um e-mail por clube: atleta A e atleta B têm e-mail. O atleta sem e-mail, o documento
        // expirado, o que está fora da janela de 30 dias e o anonimizado não geram alertas.
        assertThat(contarOutbox()).isEqualTo(2);
        assertThat(contarOutbox(EMAIL_ATLETA, "Exame Médico")).isEqualTo(1);
        assertThat(contarOutbox(EMAIL_ATLETA_B, "Cartão de Cidadão")).isEqualTo(1);

        // Correr de novo no mesmo dia não repete o alerta.
        notifier.notifyExpiring();
        assertThat(contarOutbox()).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM document WHERE id = ? AND expiry_notified_at IS NOT NULL",
                DOC_A)).isEqualTo(1);

        // Atualizar o documento (nova validade) volta a permitir o alerta.
        mvc.perform(put("/api/v1/documents/" + DOC_A)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documento("Exame Médico Renovado", "exame.pdf", null,
                                LocalDate.now().plusDays(20).toString())))
                .andExpect(status().isOk());
        notifier.notifyExpiring();
        assertThat(contarOutbox()).isEqualTo(3);
        assertThat(contarOutbox(EMAIL_ATLETA, "Exame Médico Renovado")).isEqualTo(1);
    }

    // --- auxiliares de pedido ---

    private ResultActions criarDocumento(String host, String token, UUID athleteId, String body)
            throws Exception {
        return mvc.perform(post("/api/v1/athletes/" + athleteId + "/documents")
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String documento(String type, String fileName, String fileUrl, String validUntil) {
        return "{\"type\":" + json(type) + ",\"fileName\":" + json(fileName)
                + ",\"fileUrl\":" + json(fileUrl) + ",\"validUntil\":" + json(validUntil) + "}";
    }

    private String acesso(String host, String email) throws Exception {
        String resposta = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASS + "\"}")
                        .with(host(host)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resposta, "$.accessToken");
    }

    private static String json(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static List<UUID> ids(String json) {
        List<String> valores = JsonPath.read(json, "$[*].id");
        return valores.stream().map(UUID::fromString).toList();
    }

    private static RequestPostProcessor host(String serverName) {
        return request -> {
            request.setServerName(serverName);
            return request;
        };
    }

    private static UUID id(String email) {
        return UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8));
    }

    // --- semeadura ---

    private void semear() throws SQLException {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASS);
        }
        try (Connection c = superuser()) {
            limpar(c);
            clube(c, CLUBE_A, "Clube Documentos", "clube-docs");
            clube(c, CLUBE_B, "Clube Documentos B", "clube-docs-b");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");
            membro(c, ATLETA_USER, CLUBE_A, "ATLETA");
            membro(c, ENCARREGADO, CLUBE_A, "ENCARREGADO");
            membro(c, ADMIN_B, CLUBE_B, "ADMIN");

            atleta(c, ATLETA_1, CLUBE_A, "Atleta Um", "2010-05-01", "F", "D-A1", id(ATLETA_USER),
                    EMAIL_ATLETA);
            atleta(c, ATLETA_2, CLUBE_A, "Atleta Dois", "2010-05-02", "F", "D-A2", null, null);
            atleta(c, ATLETA_B, CLUBE_B, "Atleta de Outro Clube", "2010-05-03", "F", "D-B1", null,
                    EMAIL_ATLETA_B);

            equipa(c, EQUIPA_A, CLUBE_A, "Equipa A");
            equipa(c, EQUIPA_OUTRA, CLUBE_A, "Equipa B");
            equipa(c, EQUIPA_B, CLUBE_B, "Equipa B");

            executar(c, """
                    INSERT INTO team_staff (tenant_id, team_id, user_id, role)
                    VALUES (?, ?, ?, 'TREINADOR') ON CONFLICT (tenant_id, team_id, user_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, id(TREINADOR));
            executar(c, """
                    INSERT INTO team_athlete (tenant_id, team_id, athlete_id)
                    VALUES (?, ?, ?) ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, ATLETA_1);
            executar(c, """
                    INSERT INTO team_athlete (tenant_id, team_id, athlete_id)
                    VALUES (?, ?, ?) ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_OUTRA, ATLETA_2);
            executar(c, """
                    INSERT INTO team_athlete (tenant_id, team_id, athlete_id)
                    VALUES (?, ?, ?) ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                    """, CLUBE_B, EQUIPA_B, ATLETA_B);
            executar(c, """
                    INSERT INTO athlete_guardian (tenant_id, athlete_id, user_id, relationship)
                    VALUES (?, ?, ?, 'MAE') ON CONFLICT (tenant_id, athlete_id, user_id) DO NOTHING
                    """, CLUBE_A, ATLETA_1, id(ENCARREGADO));

            documento(c, DOC_A, CLUBE_A, ATLETA_1, "Exame Médico", "CURRENT_DATE + 10", false);
            documento(c, DOC_SEM_EMAIL, CLUBE_A, ATLETA_2, "Exame Médico", "CURRENT_DATE + 10", false);
            documento(c, DOC_B, CLUBE_B, ATLETA_B, "Cartão de Cidadão", "CURRENT_DATE + 5", false);
            documento(c, DOC_EXPIRADO, CLUBE_A, ATLETA_1, "Exame Expirado", "CURRENT_DATE - 1", false);
            documento(c, DOC_LONGE, CLUBE_A, ATLETA_1, "Exame Longínquo", "CURRENT_DATE + 90", false);
            documento(c, DOC_ANONIMIZADO, CLUBE_A, ATLETA_1, "Exame Anonimizado", "CURRENT_DATE + 10", true);
        }
    }

    private static void limpar(Connection c) throws SQLException {
        executar(c, "DELETE FROM document WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
        executar(c, "DELETE FROM email_outbox");
    }

    private static void clube(Connection c, UUID tenantId, String nome, String slug) throws SQLException {
        executar(c, """
                INSERT INTO tenant (id, name, slug) VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, slug = EXCLUDED.slug
                """, tenantId, nome, slug);
    }

    private void membro(Connection c, String email, UUID tenant, String... roles) throws SQLException {
        executar(c, """
                INSERT INTO app_user (id, email, name, password_hash) VALUES (?, ?, 'Teste', ?)
                ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, name = EXCLUDED.name,
                    password_hash = EXCLUDED.password_hash, status = 'ACTIVE'
                """, id(email), email, passwordHash);
        for (String role : roles) {
            executar(c, """
                    INSERT INTO membership (tenant_id, user_id, role) VALUES (?, ?, ?)
                    ON CONFLICT (tenant_id, user_id, role) DO UPDATE SET status = 'ACTIVE'
                    """, tenant, id(email), role);
        }
    }

    private static void atleta(Connection c, UUID athleteId, UUID tenantId, String nome, String nascimento,
                               String genero, String licenca, UUID userId, String email)
            throws SQLException {
        executar(c, """
                INSERT INTO athlete (id, tenant_id, full_name, birth_date, gender, license_number, email,
                                     status, user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (id) DO UPDATE SET full_name = EXCLUDED.full_name,
                    birth_date = EXCLUDED.birth_date, gender = EXCLUDED.gender,
                    license_number = EXCLUDED.license_number, email = EXCLUDED.email,
                    status = 'ACTIVE', user_id = EXCLUDED.user_id, anonymized_at = NULL
                """, athleteId, tenantId, nome, Date.valueOf(nascimento), genero, licenca, email, userId);
    }

    private static void equipa(Connection c, UUID teamId, UUID tenantId, String nome) throws SQLException {
        executar(c, """
                INSERT INTO team (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE', anonymized_at = NULL
                """, teamId, tenantId, nome);
    }

    private static void documento(Connection c, UUID documentId, UUID tenantId, UUID athleteId,
                                  String type, String validUntilExpression, boolean anonymized)
            throws SQLException {
        executar(c, """
                INSERT INTO document (id, tenant_id, athlete_id, type, file_name, valid_until, anonymized_at)
                VALUES (?, ?, ?, ?, 'ficheiro.pdf', %s, %s)
                ON CONFLICT (id) DO UPDATE SET athlete_id = EXCLUDED.athlete_id, type = EXCLUDED.type,
                    valid_until = EXCLUDED.valid_until, anonymized_at = EXCLUDED.anonymized_at,
                    expiry_notified_at = NULL
                """.formatted(validUntilExpression, anonymized ? "now()" : "NULL"),
                documentId, tenantId, athleteId, type);
    }

    private static int contarOutbox() throws SQLException {
        return contar("SELECT count(*) FROM email_outbox");
    }

    private static int contarOutbox(String recipient, String subject) throws SQLException {
        return contar("SELECT count(*) FROM email_outbox WHERE recipient = ? AND subject = ?",
                recipient, "Documento a expirar: " + subject);
    }

    private static int contarAuditoria(String action) throws SQLException {
        return contar("SELECT count(*) FROM audit_log WHERE action = ?", action);
    }

    private static int contar(String sql, Object... params) throws SQLException {
        try (Connection c = superuser(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void executar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
