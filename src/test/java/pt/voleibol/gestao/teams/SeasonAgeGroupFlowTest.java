package pt.voleibol.gestao.teams;

import com.jayway.jsonpath.JsonPath;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Épocas e escalões (FPV) contra PostgreSQL real: CRUD, validações, leitura por qualquer membro,
 * escrita só para ADMIN/DIRECAO, isolamento entre clubes, remoção não destrutiva e associação às
 * equipas.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
class SeasonAgeGroupFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000d201");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000d202");

    static final String HOST_A = "clube-epocas.localhost";
    static final String HOST_B = "clube-epocas-b.localhost";
    static final String PASS = "palavra-passe-teste";

    static final String ADMIN = "admin@epocas.test";
    static final String DIRECAO = "direcao@epocas.test";
    static final String TREINADOR = "treinador@epocas.test";
    static final String FINANCEIRO = "financeiro@epocas.test";
    static final String ADMIN_B = "admin@epocas-b.test";

    static final UUID EPOCA_A = UUID.fromString("00000000-0000-0000-0000-00000000d211");
    static final UUID EPOCA_B = UUID.fromString("00000000-0000-0000-0000-00000000d212");
    static final UUID ESCALAO_A = UUID.fromString("00000000-0000-0000-0000-00000000d221");
    static final UUID ESCALAO_B = UUID.fromString("00000000-0000-0000-0000-00000000d222");

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

    MockMvc mvc;

    @BeforeEach
    void preparar() throws SQLException {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(tenantFilter)
                .apply(springSecurity())
                .build();
        semear();
    }

    @Test
    void adminCriaAtualizaListaEApagaEpocas() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criada = criarEpoca(HOST_A, admin, """
                {"name":"Época 2025/2026","startDate":"2025-08-01","endDate":"2026-07-31","isActive":true}
                """).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criada, "$.id"));
        assertThat((Boolean) JsonPath.read(criada, "$.isActive")).isTrue();
        assertThat((String) JsonPath.read(criada, "$.startDate")).isEqualTo("2025-08-01");

        getEpoca(HOST_A, admin, id).andExpect(status().isOk());

        String lista = listarEpocas(HOST_A, admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(lista, "$[*].name"))
                .contains("Época A", "Época 2025/2026");

        criarEpoca(HOST_A, admin, """
                {"name":"época a","startDate":"2025-08-01"}
                """).andExpect(status().isConflict());
        criarEpoca(HOST_A, admin, """
                {"name":"Época inválida","startDate":"2026-08-01","endDate":"2025-08-01"}
                """).andExpect(status().isBadRequest());

        atualizarEpoca(HOST_A, admin, id, """
                {"name":"Época 2025/2026 - atual","isActive":false}
                """).andExpect(status().isOk());
        getEpoca(HOST_A, admin, id).andExpect(status().isOk());

        apagarEpoca(HOST_A, admin, id).andExpect(status().isNoContent());
        getEpoca(HOST_A, admin, id).andExpect(status().isNotFound());
        String depois = listarEpocas(HOST_A, admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(depois, "$[*].name"))
                .doesNotContain("Época 2025/2026 - atual");
        assertThat(contarAuditoria("SEASON_ANONYMIZED")).isPositive();

        // O nome fica livre para uma nova época.
        criarEpoca(HOST_A, admin, """
                {"name":"Época 2025/2026 - atual"}
                """).andExpect(status().isCreated());
    }

    @Test
    void leituraAbertaAEscritaSoParaGestao() throws Exception {
        mvc.perform(get("/api/v1/seasons").with(host(HOST_A))).andExpect(status().isUnauthorized());

        String financeiro = acesso(HOST_A, FINANCEIRO);
        listarEpocas(HOST_A, financeiro).andExpect(status().isOk());
        getEpoca(HOST_A, financeiro, EPOCA_A).andExpect(status().isOk());
        criarEpoca(HOST_A, financeiro, "{\"name\":\"Época do Financeiro\"}")
                .andExpect(status().isForbidden());

        String treinador = acesso(HOST_A, TREINADOR);
        listarEpocas(HOST_A, treinador).andExpect(status().isOk());
        getEpoca(HOST_A, treinador, EPOCA_A).andExpect(status().isOk());
        criarEpoca(HOST_A, treinador, "{\"name\":\"Época do Treinador\"}")
                .andExpect(status().isForbidden());
        atualizarEpoca(HOST_A, treinador, EPOCA_A, "{\"name\":\"Renomeada\"}")
                .andExpect(status().isForbidden());
        apagarEpoca(HOST_A, treinador, EPOCA_A).andExpect(status().isForbidden());

        listarEscaloes(HOST_A, treinador).andExpect(status().isOk());
        criarEscalao(HOST_A, treinador, """
                {"name":"Sub-12","seasonId":"%s","minBirthYear":2012,"maxBirthYear":2014}
                """.formatted(EPOCA_A)).andExpect(status().isForbidden());

        String direcao = acesso(HOST_A, DIRECAO);
        criarEpoca(HOST_A, direcao, "{\"name\":\"Época da Direção\"}").andExpect(status().isCreated());
        criarEscalao(HOST_A, direcao, """
                {"name":"Sub-14","seasonId":"%s","minBirthYear":2010,"maxBirthYear":2012}
                """.formatted(EPOCA_A)).andExpect(status().isCreated());
    }

    @Test
    void escaloesValidamFaixaENome() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        criarEscalao(HOST_A, admin, """
                {"name":"Cadetes","seasonId":"%s","minBirthYear":2010,"maxBirthYear":2012}
                """.formatted(EPOCA_A)).andExpect(status().isConflict());
        // O escalão "Cadetes" já existe na semente.

        criarEscalao(HOST_A, admin, """
                {"name":"Juvenis","seasonId":"%s","minBirthYear":2014,"maxBirthYear":2012}
                """.formatted(EPOCA_A)).andExpect(status().isBadRequest());
        criarEscalao(HOST_A, admin, """
                {"name":"Juvenis","seasonId":"%s","minBirthYear":2014,"maxBirthYear":2014}
                """.formatted(UUID.randomUUID())).andExpect(status().isNotFound());

        String novo = criarEscalao(HOST_A, admin, """
                {"name":"Juvenis","seasonId":"%s","minBirthYear":2014,"maxBirthYear":2014}
                """.formatted(EPOCA_A)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(novo, "$.id"));
        assertThat((Integer) JsonPath.read(novo, "$.minBirthYear")).isEqualTo(2014);
        assertThat((Integer) JsonPath.read(novo, "$.maxBirthYear")).isEqualTo(2014);

        atualizarEscalao(HOST_A, admin, id, """
                {"name":"Juvenis A","seasonId":"%s","minBirthYear":2014,"maxBirthYear":2016}
                """.formatted(EPOCA_A)).andExpect(status().isOk());

        apagarEscalao(HOST_A, admin, id).andExpect(status().isNoContent());
        getEscalao(HOST_A, admin, id).andExpect(status().isNotFound());
        String lista = listarEscaloes(HOST_A, admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(lista, "$[*].name")).doesNotContain("Juvenis A");
        // Após a anonimização o nome liberta-se e pode ser reutilizado.
        criarEscalao(HOST_A, admin, """
                {"name":"Juvenis A","seasonId":"%s","minBirthYear":2014,"maxBirthYear":2016}
                """.formatted(EPOCA_A)).andExpect(status().isCreated());
        assertThat(contarAuditoria("AGE_GROUP_ANONYMIZED")).isPositive();
        assertThat(contarAuditoria("AGE_GROUP_DEACTIVATED")).isZero();
    }

    @Test
    void equipaAssociaEpocaEEscalao() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criada = mvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 A","seasonId":"%s","ageGroupId":"%s"}
                                """.formatted(EPOCA_A, ESCALAO_A)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID teamId = UUID.fromString(JsonPath.read(criada, "$.id"));
        assertThat((String) JsonPath.read(criada, "$.seasonId")).isEqualTo(EPOCA_A.toString());
        assertThat((String) JsonPath.read(criada, "$.ageGroupId")).isEqualTo(ESCALAO_A.toString());

        String atualizada = mvc.perform(put("/api/v1/teams/" + teamId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 A","seasonId":"%s","ageGroupId":"%s"}
                                """.formatted(EPOCA_A, ESCALAO_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(atualizada, "$.seasonId")).isEqualTo(EPOCA_A.toString());

        // PUT sem associações limpa os campos.
        String limpa = mvc.perform(put("/api/v1/teams/" + teamId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 A"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(limpa, "$.seasonId")).isNull();
        assertThat((Object) JsonPath.read(limpa, "$.ageGroupId")).isNull();

        // O escalão tem de pertencer à época da equipa: o escalão "Cadetes" pertence à "Época A", não à nova.
        String epocaC = criarEpoca(HOST_A, admin, "{\"name\":\"Época C\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID EPOCA_C = UUID.fromString(JsonPath.read(epocaC, "$.id"));
        mvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 B","seasonId":"%s","ageGroupId":"%s"}
                                """.formatted(EPOCA_C, ESCALAO_A)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 C","seasonId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        apagarEpoca(HOST_A, admin, EPOCA_A).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 D","seasonId":"%s"}
                                """.formatted(EPOCA_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void isolamentoEntreClubes() throws Exception {
        String adminA = acesso(HOST_A, ADMIN);
        String adminB = acesso(HOST_B, ADMIN_B);

        getEpoca(HOST_A, adminA, EPOCA_B).andExpect(status().isNotFound());
        atualizarEpoca(HOST_A, adminA, EPOCA_B, "{\"name\":\"Intrusa\"}")
                .andExpect(status().isNotFound());
        apagarEpoca(HOST_A, adminA, EPOCA_B).andExpect(status().isNotFound());

        String listaA = listarEpocas(HOST_A, adminA).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(listaA, "$[*].name")).doesNotContain("Época B");
        String listaB = listarEpocas(HOST_B, adminB).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(listaB, "$[*].name")).containsExactly("Época B");

        getEscalao(HOST_A, adminA, ESCALAO_B).andExpect(status().isNotFound());
        String escaloesA = listarEscaloes(HOST_A, adminA).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(escaloesA, "$[*].name")).containsExactly("Cadetes");
    }

    // --- auxiliares ---

    private ResultActions listarEpocas(String host, String token) throws Exception {
        return mvc.perform(get("/api/v1/seasons").header("Authorization", "Bearer " + token)
                .with(host(host)));
    }

    private ResultActions getEpoca(String host, String token, UUID seasonId) throws Exception {
        return mvc.perform(get("/api/v1/seasons/" + seasonId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions criarEpoca(String host, String token, String json) throws Exception {
        return mvc.perform(post("/api/v1/seasons").header("Authorization", "Bearer " + token)
                .with(host(host)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions atualizarEpoca(String host, String token, UUID seasonId, String json)
            throws Exception {
        return mvc.perform(put("/api/v1/seasons/" + seasonId)
                .header("Authorization", "Bearer " + token).with(host(host)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions apagarEpoca(String host, String token, UUID seasonId) throws Exception {
        return mvc.perform(delete("/api/v1/seasons/" + seasonId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions listarEscaloes(String host, String token) throws Exception {
        return mvc.perform(get("/api/v1/age-groups").header("Authorization", "Bearer " + token)
                .with(host(host)));
    }

    private ResultActions getEscalao(String host, String token, UUID ageGroupId) throws Exception {
        return mvc.perform(get("/api/v1/age-groups/" + ageGroupId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions criarEscalao(String host, String token, String json) throws Exception {
        return mvc.perform(post("/api/v1/age-groups").header("Authorization", "Bearer " + token)
                .with(host(host)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions atualizarEscalao(String host, String token, UUID ageGroupId, String json)
            throws Exception {
        return mvc.perform(put("/api/v1/age-groups/" + ageGroupId)
                .header("Authorization", "Bearer " + token).with(host(host)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions apagarEscalao(String host, String token, UUID ageGroupId) throws Exception {
        return mvc.perform(delete("/api/v1/age-groups/" + ageGroupId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private String acesso(String host, String email) throws Exception {
        String resposta = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + PASS + "\"}")
                        .with(host(host)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resposta, "$.accessToken");
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
            // Mantém cada teste independente dentro do mesmo contentor.
            executar(c, "DELETE FROM team_athlete");
            executar(c, "DELETE FROM team_staff");
            executar(c, "DELETE FROM team");
            executar(c, "DELETE FROM season_promotion");
            executar(c, "DELETE FROM age_group");
            executar(c, "DELETE FROM season");

            clube(c, CLUBE_A, "Clube Epocas", "clube-epocas");
            clube(c, CLUBE_B, "Clube Epocas B", "clube-epocas-b");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");
            membro(c, ADMIN_B, CLUBE_B, "ADMIN");

            epoca(c, EPOCA_A, CLUBE_A, "Época A");
            epoca(c, EPOCA_B, CLUBE_B, "Época B");
            escalao(c, ESCALAO_A, CLUBE_A, EPOCA_A, "Cadetes", 2010, 2012);
            escalao(c, ESCALAO_B, CLUBE_B, EPOCA_B, "Sub-16 B", 2012, 2014);
        }
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

    private static void epoca(Connection c, UUID seasonId, UUID tenantId, String nome) throws SQLException {
        executar(c, """
                INSERT INTO season (id, tenant_id, name, start_date, end_date, is_active)
                VALUES (?, ?, ?, ?, ?, true)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, start_date = EXCLUDED.start_date,
                    end_date = EXCLUDED.end_date, is_active = true, anonymized_at = NULL
                """, seasonId, tenantId, nome, Date.valueOf("2025-08-01"), Date.valueOf("2026-07-31"));
    }

    private static void escalao(Connection c, UUID ageGroupId, UUID tenantId, UUID seasonId, String nome,
                                 int minBirthYear, int maxBirthYear) throws SQLException {
        executar(c, """
                INSERT INTO age_group (id, tenant_id, season_id, name, min_birth_year, max_birth_year)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, season_id = EXCLUDED.season_id,
                    min_birth_year = EXCLUDED.min_birth_year, max_birth_year = EXCLUDED.max_birth_year,
                    anonymized_at = NULL
                """, ageGroupId, tenantId, seasonId, nome, minBirthYear, maxBirthYear);
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
