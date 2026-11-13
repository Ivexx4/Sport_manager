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
 * Base mínima de equipas contra PostgreSQL real: CRUD, isolamento entre clubes, staff e plantel,
 * e o âmbito do TREINADOR (só vê as equipas em que é staff) sem poder gerir plantel/staff.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
class TeamFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000d101");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000d102");

    static final String HOST_A = "clube-equipas.localhost";
    static final String HOST_B = "clube-equipas-b.localhost";
    static final String PASS = "palavra-passe-teste";

    static final String ADMIN = "admin@equipas.test";
    static final String DIRECAO = "direcao@equipas.test";
    static final String TREINADOR = "treinador@equipas.test";
    static final String FINANCEIRO = "financeiro@equipas.test";
    static final String ATLETA = "atleta@equipas.test";
    static final String ADMIN_B = "admin@equipas-b.test";

    static final UUID EQUIPA_A = UUID.fromString("00000000-0000-0000-0000-00000000e101");
    static final UUID EQUIPA_B = UUID.fromString("00000000-0000-0000-0000-00000000e102");
    static final UUID EQUIPA_CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000e103");

    static final UUID EPOCA_A = UUID.fromString("00000000-0000-0000-0000-00000000e201");
    static final UUID ESCALAO_A = UUID.fromString("00000000-0000-0000-0000-00000000e211");

    static final UUID ATLETA_A = UUID.fromString("00000000-0000-0000-0000-00000000f201");
    static final UUID ATLETA_B = UUID.fromString("00000000-0000-0000-0000-00000000f202");
    static final UUID ATLETA_C = UUID.fromString("00000000-0000-0000-0000-00000000f203");
    static final UUID ATLETA_D = UUID.fromString("00000000-0000-0000-0000-00000000f204");

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
    void adminCriaAtualizaListaEApagaEquipas() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criada = criar(HOST_A, admin, "Sub-18")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criada, "$.id"));
        assertThat((String) JsonPath.read(criada, "$.status")).isEqualTo("ACTIVE");

        getEquipa(HOST_A, admin, id).andExpect(status().isOk());
        atualizar(HOST_A, admin, id, "Sub-18 A").andExpect(status().isOk());

        String lista = listar(HOST_A, admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(lista, "$[*].name")).contains("Sub-18 A", "Equipa A");

        criar(HOST_A, admin, "equipa a").andExpect(status().isConflict());
        criar(HOST_A, admin, " ").andExpect(status().isBadRequest());

        apagar(HOST_A, admin, id).andExpect(status().isNoContent());
        getEquipa(HOST_A, admin, id).andExpect(status().isNotFound());
        assertThat(contarAuditoria("TEAM_CREATED")).isPositive();
    }

    @Test
    void autorizacaoDaListagemEPorEquipa() throws Exception {
        mvc.perform(get("/api/v1/teams").with(host(HOST_A))).andExpect(status().isUnauthorized());

        String direcao = acesso(HOST_A, DIRECAO);
        criar(HOST_A, direcao, "Equipa da Direcao").andExpect(status().isCreated());

        String financeiro = acesso(HOST_A, FINANCEIRO);
        String atleta = acesso(HOST_A, ATLETA);
        listar(HOST_A, financeiro).andExpect(status().isForbidden());
        listar(HOST_A, atleta).andExpect(status().isForbidden());

        String treinador = acesso(HOST_A, TREINADOR);
        String lista = listar(HOST_A, treinador).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(lista, "$[*].name")).containsExactly("Equipa A");

        getEquipa(HOST_A, treinador, EQUIPA_A).andExpect(status().isOk());
        getEquipa(HOST_A, treinador, EQUIPA_B).andExpect(status().isNotFound());

        criar(HOST_A, treinador, "Equipa do Treinador").andExpect(status().isForbidden());
        atualizar(HOST_A, treinador, EQUIPA_A, "Renomeada").andExpect(status().isForbidden());
        apagar(HOST_A, treinador, EQUIPA_A).andExpect(status().isForbidden());
    }

    @Test
    void staffDaEquipaDefineOVisibilidadeDoTreinador() throws Exception {
        String admin = acesso(HOST_A, ADMIN);
        String treinador = acesso(HOST_A, TREINADOR);

        getEquipa(HOST_A, treinador, EQUIPA_B).andExpect(status().isNotFound());

        adicionarStaff(HOST_A, admin, EQUIPA_B, id(TREINADOR), "TREINADOR_ADJUNTO")
                .andExpect(status().isOk());
        getEquipa(HOST_A, treinador, EQUIPA_B).andExpect(status().isOk());
        adicionarStaff(HOST_A, admin, EQUIPA_B, id(TREINADOR), "TREINADOR_ADJUNTO")
                .andExpect(status().isConflict());

        adicionarStaff(HOST_A, admin, EQUIPA_B, UUID.randomUUID(), "TREINADOR")
                .andExpect(status().isNotFound());
        adicionarStaff(HOST_A, admin, EQUIPA_B, id(ADMIN_B), "TREINADOR")
                .andExpect(status().isNotFound());
        adicionarStaff(HOST_A, admin, EQUIPA_B, id(TREINADOR), "PRESIDENTE")
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/v1/teams/" + EQUIPA_B + "/staff/" + id(TREINADOR))
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        getEquipa(HOST_A, treinador, EQUIPA_B).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/teams/" + EQUIPA_B + "/staff/" + id(TREINADOR))
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        // Treinador pode ler o staff da sua equipa, mas não o alterar.
        mvc.perform(get("/api/v1/teams/" + EQUIPA_A + "/staff")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isOk());
        adicionarStaff(HOST_A, treinador, EQUIPA_A, id(ATLETA), "TREINADOR")
                .andExpect(status().isForbidden());
    }

    @Test
    void plantelDaEquipa() throws Exception {
        String admin = acesso(HOST_A, ADMIN);
        String treinador = acesso(HOST_A, TREINADOR);

        String plantel = mvc.perform(get("/api/v1/teams/" + EQUIPA_A + "/athletes")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(plantel, "$[*].athleteId")).containsExactly(ATLETA_A.toString());

        adicionarAtleta(HOST_A, admin, EQUIPA_B, ATLETA_A).andExpect(status().isOk());
        adicionarAtleta(HOST_A, admin, EQUIPA_B, ATLETA_A).andExpect(status().isConflict());
        adicionarAtleta(HOST_A, admin, EQUIPA_B, UUID.randomUUID()).andExpect(status().isNotFound());
        adicionarAtleta(HOST_A, admin, EQUIPA_B, ATLETA_B).andExpect(status().isNotFound());
        adicionarAtleta(HOST_A, treinador, EQUIPA_A, ATLETA_B).andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/teams/" + EQUIPA_B + "/athletes/" + ATLETA_A)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/teams/" + EQUIPA_B + "/athletes/" + ATLETA_A)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void validacaoFpVDoPlantel() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        // Atlea dentro da faixa do escalão (nascimento entre min e max): permite a inserção.
        adicionarAtleta(HOST_A, admin, EQUIPA_B, ATLETA_A).andExpect(status().isOk());

        // Atlea mais velho do que o escalão: proibido atuar em escalão inferior conforme regulamento FPV.
        adicionarAtleta(HOST_A, admin, EQUIPA_B, ATLETA_C).andExpect(status().isBadRequest());

        // Atlea mais novo do que o escalão: exige autorização excepcional de subida de escalão.
        adicionarAtleta(HOST_A, admin, EQUIPA_B, ATLETA_D).andExpect(status().isBadRequest());

        // Com autorização excepcional ativa para a época, a subida de escalão é permitida.
        try (Connection c = superuser()) {
            executar(c, """
                    INSERT INTO season_promotion (tenant_id, season_id, athlete_id, age_group_id, authorized_by, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """, CLUBE_A, EPOCA_A, ATLETA_D, ESCALAO_A, id(ADMIN));
        }
        adicionarAtleta(HOST_A, admin, EQUIPA_B, ATLETA_D).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/teams/" + EQUIPA_B + "/athletes/" + ATLETA_D)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());

        // Após revogar a autorização, a subida de escalão volta a ser recusada.
        try (Connection c = superuser()) {
            executar(c, """
                    UPDATE season_promotion
                    SET status = 'REVOKED'
                    WHERE tenant_id = ? AND season_id = ? AND athlete_id = ? AND age_group_id = ?
                    """, CLUBE_A, EPOCA_A, ATLETA_D, ESCALAO_A);
        }
        adicionarAtleta(HOST_A, admin, EQUIPA_B, ATLETA_D).andExpect(status().isBadRequest());
    }

    @Test
    void isolamentoEntreClubes() throws Exception {
        String adminA = acesso(HOST_A, ADMIN);
        String adminB = acesso(HOST_B, ADMIN_B);

        getEquipa(HOST_A, adminA, EQUIPA_CLUBE_B).andExpect(status().isNotFound());
        atualizar(HOST_A, adminA, EQUIPA_CLUBE_B, "Intrusa").andExpect(status().isNotFound());
        apagar(HOST_A, adminA, EQUIPA_CLUBE_B).andExpect(status().isNotFound());

        String listaA = listar(HOST_A, adminA).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(listaA, "$[*].name"))
                .doesNotContain("Equipa do Clube B");
        String listaB = listar(HOST_B, adminB).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(listaB, "$[*].name"))
                .containsExactly("Equipa do Clube B");

        adicionarStaff(HOST_A, adminA, EQUIPA_CLUBE_B, id(TREINADOR), "TREINADOR")
                .andExpect(status().isNotFound());
        adicionarAtleta(HOST_A, adminA, EQUIPA_CLUBE_B, ATLETA_A).andExpect(status().isNotFound());
    }

    @Test
    void remocaoAnonimizaEquipaEMantemStaffEPlantel() throws Exception {
        String admin = acesso(HOST_A, ADMIN);
        String treinador = acesso(HOST_A, TREINADOR);

        String criada = criar(HOST_A, admin, "Equipa Descartavel", EPOCA_A, ESCALAO_A)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criada, "$.id"));
        adicionarStaff(HOST_A, admin, id, id(TREINADOR), "TREINADOR").andExpect(status().isOk());
        adicionarAtleta(HOST_A, admin, id, ATLETA_A).andExpect(status().isOk());

        apagar(HOST_A, admin, id).andExpect(status().isNoContent());

        // A linha mantém-se, desativada e anonimizada; as relações de staff/plantel preservam-se.
        assertThat(contar("""
                SELECT count(*) FROM team
                WHERE id = ? AND status = 'INACTIVE' AND anonymized_at IS NOT NULL
                  AND name = 'Equipa anonimizada'
                """, id)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM team_staff WHERE team_id = ?", id)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM team_athlete WHERE team_id = ?", id)).isEqualTo(1);
        getEquipa(HOST_A, treinador, id).andExpect(status().isNotFound());
        getEquipa(HOST_A, admin, id).andExpect(status().isNotFound());

        // O nome fica livre para uma nova equipa.
        criar(HOST_A, admin, "Equipa Descartavel").andExpect(status().isCreated());
        assertThat(contarAuditoria("TEAM_ANONYMIZED")).isPositive();
    }

    // --- auxiliares ---

    private ResultActions listar(String host, String token) throws Exception {
        return mvc.perform(get("/api/v1/teams").header("Authorization", "Bearer " + token)
                .with(host(host)));
    }

    private ResultActions getEquipa(String host, String token, UUID teamId) throws Exception {
        return mvc.perform(get("/api/v1/teams/" + teamId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions criar(String host, String token, String nome) throws Exception {
        return mvc.perform(post("/api/v1/teams").header("Authorization", "Bearer " + token)
                .with(host(host)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + nome + "\"}"));
    }

    private ResultActions criar(String host, String token, String nome, UUID seasonId, UUID ageGroupId)
            throws Exception {
        return mvc.perform(post("/api/v1/teams").header("Authorization", "Bearer " + token)
                .with(host(host)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + nome + "\",\"seasonId\":\"" + seasonId + "\",\"ageGroupId\":\"" + ageGroupId + "\"}"));
    }

    private ResultActions atualizar(String host, String token, UUID teamId, String nome) throws Exception {
        return mvc.perform(put("/api/v1/teams/" + teamId)
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + nome + "\"}"));
    }

    private ResultActions apagar(String host, String token, UUID teamId) throws Exception {
        return mvc.perform(delete("/api/v1/teams/" + teamId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions adicionarStaff(String host, String token, UUID teamId, UUID userId, String role)
            throws Exception {
        return mvc.perform(post("/api/v1/teams/" + teamId + "/staff")
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}"));
    }

    private ResultActions adicionarAtleta(String host, String token, UUID teamId, UUID athleteId)
            throws Exception {
        return mvc.perform(post("/api/v1/teams/" + teamId + "/athletes")
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"athleteId\":\"" + athleteId + "\"}"));
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
            executar(c, "DELETE FROM team_athlete");
            executar(c, "DELETE FROM team_staff");
            executar(c, "DELETE FROM team");
            executar(c, "DELETE FROM season_promotion");
            executar(c, "DELETE FROM age_group");
            executar(c, "DELETE FROM season");
            clube(c, CLUBE_A, "Clube Equipas", "clube-equipas");
            clube(c, CLUBE_B, "Clube Equipas B", "clube-equipas-b");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");
            membro(c, ATLETA, CLUBE_A, "ATLETA");
            membro(c, ADMIN_B, CLUBE_B, "ADMIN");

            atleta(c, ATLETA_A, CLUBE_A, "Atleta A");
            atleta(c, ATLETA_B, CLUBE_B, "Atleta B");
            atleta(c, ATLETA_C, CLUBE_A, "Atleta Velho", 2005);
            atleta(c, ATLETA_D, CLUBE_A, "Atleta Novo", 2018);

            epoca(c, EPOCA_A, CLUBE_A, "Época A");
            escalao(c, ESCALAO_A, CLUBE_A, "Sub-14", 2008, 2012);

            equipa(c, EQUIPA_A, CLUBE_A, "Equipa A", EPOCA_A, ESCALAO_A);
            equipa(c, EQUIPA_B, CLUBE_A, "Equipa B", EPOCA_A, ESCALAO_A);
            equipa(c, EQUIPA_CLUBE_B, CLUBE_B, "Equipa do Clube B", null, null);

            executar(c, """
                    INSERT INTO team_staff (tenant_id, team_id, user_id, role)
                    VALUES (?, ?, ?, 'TREINADOR') ON CONFLICT (tenant_id, team_id, user_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, id(TREINADOR));
            executar(c, """
                    INSERT INTO team_athlete (tenant_id, team_id, athlete_id)
                    VALUES (?, ?, ?) ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, ATLETA_A);
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

    private static void atleta(Connection c, UUID athleteId, UUID tenantId, String nome) throws SQLException {
        atleta(c, athleteId, tenantId, nome, 2010);
    }

    private static void atleta(Connection c, UUID athleteId, UUID tenantId, String nome, int birthYear)
            throws SQLException {
        executar(c, """
                INSERT INTO athlete (id, tenant_id, full_name, birth_date, gender, status)
                VALUES (?, ?, ?, ?, 'F', 'ACTIVE')
                ON CONFLICT (id) DO UPDATE SET full_name = EXCLUDED.full_name,
                    birth_date = EXCLUDED.birth_date, gender = EXCLUDED.gender,
                    status = 'ACTIVE', anonymized_at = NULL
                """, athleteId, tenantId, nome, Date.valueOf(birthYear + "-06-15"));
    }

    private static void epoca(Connection c, UUID seasonId, UUID tenantId, String nome) throws SQLException {
        executar(c, """
                INSERT INTO season (id, tenant_id, name, start_date, end_date, is_active)
                VALUES (?, ?, ?, ?, ?, true)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, start_date = EXCLUDED.start_date,
                    end_date = EXCLUDED.end_date, is_active = true, anonymized_at = NULL
                """, seasonId, tenantId, nome, Date.valueOf("2025-08-01"), Date.valueOf("2026-07-31"));
    }

    private static void escalao(Connection c, UUID ageGroupId, UUID tenantId, String nome, int minBirthYear,
                                int maxBirthYear) throws SQLException {
        executar(c, """
                INSERT INTO age_group (id, tenant_id, season_id, name, min_birth_year, max_birth_year)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, season_id = EXCLUDED.season_id,
                    min_birth_year = EXCLUDED.min_birth_year, max_birth_year = EXCLUDED.max_birth_year,
                    anonymized_at = NULL
                """, ageGroupId, tenantId, EPOCA_A, nome, minBirthYear, maxBirthYear);
    }

    private static void equipa(Connection c, UUID teamId, UUID tenantId, String nome, UUID seasonId,
                               UUID ageGroupId) throws SQLException {
        executar(c, """
                INSERT INTO team (id, tenant_id, name, status, season_id, age_group_id)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE',
                    season_id = EXCLUDED.season_id, age_group_id = EXCLUDED.age_group_id,
                    anonymized_at = NULL
                """, teamId, tenantId, nome, seasonId, ageGroupId);
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
