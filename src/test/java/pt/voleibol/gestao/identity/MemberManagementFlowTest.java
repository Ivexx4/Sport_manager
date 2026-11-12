package pt.voleibol.gestao.identity;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gestão de membros ({@code /api/v1/users}) contra PostgreSQL real: listagem com papéis/estados,
 * desativação, remoção de papéis, regras de autorização (ADMIN/DIRECAO, gestão de gestores, própria
 * conta e último ADMIN ativo) e revogação de sessões.
 *
 * <p>Cada teste usa membros dedicados para não depender da ordem de execução. Os testes do último
 * ADMIN aproveitam o facto de um access token continuar válido durante 15 minutos depois de a
 * membership ser desativada, para reproduzir a corrida que a verificação de 409 protege.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
class MemberManagementFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000f101");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000f102");
    static final UUID CLUBE_C = UUID.fromString("00000000-0000-0000-0000-00000000f103");
    static final UUID CLUBE_D = UUID.fromString("00000000-0000-0000-0000-00000000f104");

    static final String HOST_A = "clube-membros.localhost";
    static final String HOST_B = "clube-membros-b.localhost";
    static final String HOST_C = "clube-membros-c.localhost";
    static final String HOST_D = "clube-membros-d.localhost";

    static final String PASS = "palavra-passe-teste";

    static final String ADMIN = "admin@membros.test";
    static final String DIRECAO = "direcao@membros.test";
    static final String TREINADOR = "treinador@membros.test";
    static final String FINANCEIRO = "financeiro@membros.test";

    static final String ALVO_LISTA = "alvo-lista@membros.test";
    static final String ALVO_DISABLE = "alvo-disable@membros.test";
    static final String ALVO_REMOVER = "alvo-remover@membros.test";
    static final String ALVO_ULTIMO = "alvo-ultimo@membros.test";
    static final String ALVO_ADMIN = "alvo-admin@membros.test";
    static final String ALVO_ADMIN2 = "alvo-admin2@membros.test";
    static final String ALVO_DIRECAO = "alvo-direcao@membros.test";
    static final String ALVO_SIMPLES = "alvo-simples@membros.test";

    static final String ADMIN_B = "admin@membros-b.test";
    static final String SO_B = "so-b@membros-b.test";

    static final String RACE_C_ACTOR = "race-c-actor@membros-c.test";
    static final String RACE_C_UNICO = "race-c-unico@membros-c.test";
    static final String RACE_D_ACTOR = "race-d-actor@membros-d.test";
    static final String RACE_D_UNICO = "race-d-unico@membros-d.test";

    /** O mesmo hash para todos os membros de teste: evita calcular bcrypt dezenas de vezes. */
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

    // --- listagem ---

    @Test
    void listaMembrosDoClubeComPapeisEEstados() throws Exception {
        String admin = acesso(HOST_A, ADMIN, PASS);
        String json = listar(HOST_A, admin);

        assertThat(emails(json)).contains(ADMIN, DIRECAO, ALVO_LISTA);
        assertThat(emails(json)).doesNotContain(ADMIN_B, SO_B);

        Map<String, Object> alvo = unico(json, ALVO_LISTA);
        assertThat(alvo.get("email")).isEqualTo(ALVO_LISTA);
        assertThat(alvo.get("status")).isEqualTo("ACTIVE");
        assertThat(papeis(alvo)).containsExactly(Map.entry("ATLETA", "ACTIVE"));
    }

    @Test
    void gestaoDeMembrosExigeAdminOuDirecao() throws Exception {
        mvc.perform(get("/api/v1/users").with(host(HOST_A))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/users/" + id(ALVO_SIMPLES) + "/disable").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/users/" + id(ALVO_SIMPLES) + "/roles/TREINADOR").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + acesso(HOST_A, TREINADOR, PASS))
                        .with(host(HOST_A)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + acesso(HOST_A, FINANCEIRO, PASS))
                        .with(host(HOST_A)))
                .andExpect(status().isForbidden());

        // DIRECAO pode listar.
        listar(HOST_A, acesso(HOST_A, DIRECAO, PASS));
    }

    // --- desativação ---

    @Test
    void desativaMembroERevogaAsSessoes() throws Exception {
        String admin = acesso(HOST_A, ADMIN, PASS);
        String refresh = refreshDe(login(HOST_A, ALVO_DISABLE, PASS));

        desativar(HOST_A, admin, id(ALVO_DISABLE)).andExpect(status().isNoContent());

        Map<String, Object> alvo = unico(listar(HOST_A, admin), ALVO_DISABLE);
        assertThat(papeis(alvo))
                .containsEntry("ATLETA", "DISABLED")
                .containsEntry("TREINADOR", "DISABLED");
        // A conta continua ativa; o que fica desativado são as relações com o clube.
        assertThat(alvo.get("status")).isEqualTo("ACTIVE");

        login(HOST_A, ALVO_DISABLE, PASS).andExpect(status().isUnauthorized());
        refresh(HOST_A, refresh).andExpect(status().isUnauthorized());
        assertThat(contarAuditoria("MEMBER_DISABLED")).isPositive();
    }

    @Test
    void naoDesativaNemRetiraOPapelProprio() throws Exception {
        String admin = acesso(HOST_A, ADMIN, PASS);
        desativar(HOST_A, admin, id(ADMIN)).andExpect(status().isBadRequest());
        removerPapel(HOST_A, admin, id(ADMIN), "ADMIN").andExpect(status().isBadRequest());

        String direcao = acesso(HOST_A, DIRECAO, PASS);
        desativar(HOST_A, direcao, id(DIRECAO)).andExpect(status().isBadRequest());
        removerPapel(HOST_A, direcao, id(DIRECAO), "DIRECAO").andExpect(status().isBadRequest());
    }

    @Test
    void direcaoNaoGerenciaMembrosComPapeisDeGestao() throws Exception {
        String direcao = acesso(HOST_A, DIRECAO, PASS);

        desativar(HOST_A, direcao, id(ALVO_ADMIN)).andExpect(status().isForbidden());
        removerPapel(HOST_A, direcao, id(ALVO_ADMIN), "ADMIN").andExpect(status().isForbidden());
        removerPapel(HOST_A, direcao, id(ALVO_ADMIN), "ATLETA").andExpect(status().isForbidden());
        removerPapel(HOST_A, direcao, id(ALVO_DIRECAO), "DIRECAO").andExpect(status().isForbidden());

        // Membros sem papéis de gestão: permitido.
        desativar(HOST_A, direcao, id(ALVO_SIMPLES)).andExpect(status().isNoContent());
    }

    @Test
    void adminRemoveOPapelDeOutroAdmin() throws Exception {
        String admin = acesso(HOST_A, ADMIN, PASS);

        removerPapel(HOST_A, admin, id(ALVO_ADMIN2), "ADMIN").andExpect(status().isNoContent());

        assertThat(contar("SELECT count(*) FROM membership WHERE tenant_id = ? AND user_id = ?",
                CLUBE_A, id(ALVO_ADMIN2))).isZero();
        assertThat(contarAuditoria("MEMBER_ROLE_REMOVED")).isPositive();
    }

    // --- remoção de papéis ---

    @Test
    void removerUmPapelMantemAsOutrasSessoes() throws Exception {
        String admin = acesso(HOST_A, ADMIN, PASS);
        String refresh = refreshDe(login(HOST_A, ALVO_REMOVER, PASS));

        removerPapel(HOST_A, admin, id(ALVO_REMOVER), "FINANCEIRO").andExpect(status().isNoContent());

        Map<String, Object> alvo = unico(listar(HOST_A, admin), ALVO_REMOVER);
        assertThat(papeis(alvo))
                .containsEntry("ATLETA", "ACTIVE")
                .doesNotContainKey("FINANCEIRO");

        // Ainda tem um papel ativo: as sessões continuam válidas.
        refresh(HOST_A, refresh).andExpect(status().isOk());

        removerPapel(HOST_A, admin, id(ALVO_REMOVER), "FINANCEIRO").andExpect(status().isNotFound());
        removerPapel(HOST_A, admin, id(ALVO_REMOVER), "ADMIN").andExpect(status().isNotFound());
        removerPapel(HOST_A, admin, id(ALVO_REMOVER), "SUPERUTILIZADOR").andExpect(status().isBadRequest());
    }

    @Test
    void removerOUltimoPapelRevogaAsSessoes() throws Exception {
        String admin = acesso(HOST_A, ADMIN, PASS);
        String refresh = refreshDe(login(HOST_A, ALVO_ULTIMO, PASS));

        removerPapel(HOST_A, admin, id(ALVO_ULTIMO), "ATLETA").andExpect(status().isNoContent());

        refresh(HOST_A, refresh).andExpect(status().isUnauthorized());
        login(HOST_A, ALVO_ULTIMO, PASS).andExpect(status().isUnauthorized());
    }

    // --- isolamento e alvos inexistentes ---

    @Test
    void membroDeOutroClubeOuInexistenteDa404() throws Exception {
        String admin = acesso(HOST_A, ADMIN, PASS);

        desativar(HOST_A, admin, id(SO_B)).andExpect(status().isNotFound());
        removerPapel(HOST_A, admin, id(SO_B), "ATLETA").andExpect(status().isNotFound());

        UUID inexistente = UUID.randomUUID();
        desativar(HOST_A, admin, inexistente).andExpect(status().isNotFound());
        removerPapel(HOST_A, admin, inexistente, "ATLETA").andExpect(status().isNotFound());
    }

    // --- último admin ativo (corrida entre pedidos) ---

    @Test
    void naoDesativaOUltimoAdminAtivo() throws Exception {
        String token = acesso(HOST_C, RACE_C_ACTOR, PASS);
        desativarMembershipComoSuperuser(CLUBE_C, RACE_C_ACTOR);

        desativar(HOST_C, token, id(RACE_C_UNICO)).andExpect(status().isConflict());

        assertThat(contar("""
                SELECT count(*) FROM membership
                WHERE tenant_id = ? AND user_id = ? AND role = 'ADMIN' AND status = 'ACTIVE'
                """, CLUBE_C, id(RACE_C_UNICO))).isEqualTo(1);
    }

    @Test
    void naoRemoveOPapelDoUltimoAdminAtivo() throws Exception {
        String token = acesso(HOST_D, RACE_D_ACTOR, PASS);
        desativarMembershipComoSuperuser(CLUBE_D, RACE_D_ACTOR);

        removerPapel(HOST_D, token, id(RACE_D_UNICO), "ADMIN").andExpect(status().isConflict());

        assertThat(contar("""
                SELECT count(*) FROM membership
                WHERE tenant_id = ? AND user_id = ? AND role = 'ADMIN' AND status = 'ACTIVE'
                """, CLUBE_D, id(RACE_D_UNICO))).isEqualTo(1);
    }

    // --- auxiliares ---

    private String listar(String host, String token) throws Exception {
        return mvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .with(host(host)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions desativar(String host, String token, UUID userId) throws Exception {
        return mvc.perform(post("/api/v1/users/" + userId + "/disable")
                .header("Authorization", "Bearer " + token)
                .with(host(host)));
    }

    private ResultActions removerPapel(String host, String token, UUID userId, String role) throws Exception {
        return mvc.perform(delete("/api/v1/users/" + userId + "/roles/" + role)
                .header("Authorization", "Bearer " + token)
                .with(host(host)));
    }

    private ResultActions login(String host, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(host(host)));
    }

    private ResultActions refresh(String host, String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}")
                .with(host(host)));
    }

    private String acesso(String host, String email, String password) throws Exception {
        return JsonPath.read(login(host, email, password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private static String refreshDe(ResultActions login) throws Exception {
        return JsonPath.read(login.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.refreshToken");
    }

    private static List<String> emails(String json) {
        return JsonPath.read(json, "$[*].email");
    }

    private static Map<String, Object> unico(String json, String email) {
        List<Map<String, Object>> encontrados = JsonPath.read(json, "$[?(@.email == '" + email + "')]");
        assertThat(encontrados).as("membro " + email).hasSize(1);
        return encontrados.get(0);
    }

    private static Map<String, String> papeis(Map<String, Object> membro) {
        Map<String, String> resultado = new LinkedHashMap<>();
        for (Object item : (List<?>) membro.get("roles")) {
            Map<?, ?> papel = (Map<?, ?>) item;
            resultado.put((String) papel.get("role"), (String) papel.get("status"));
        }
        return resultado;
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

    /** Clubes e membros de teste. Idempotente; repõe os atores das corridas como ativos. */
    private void semear() throws SQLException {
        try (Connection c = superuser()) {
            clube(c, CLUBE_A, "Clube Membros", "clube-membros");
            clube(c, CLUBE_B, "Clube Membros B", "clube-membros-b");
            clube(c, CLUBE_C, "Clube Membros C", "clube-membros-c");
            clube(c, CLUBE_D, "Clube Membros D", "clube-membros-d");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");

            membro(c, ALVO_LISTA, CLUBE_A, "ATLETA");
            membro(c, ALVO_DISABLE, CLUBE_A, "ATLETA", "TREINADOR");
            membro(c, ALVO_REMOVER, CLUBE_A, "ATLETA", "FINANCEIRO");
            membro(c, ALVO_ULTIMO, CLUBE_A, "ATLETA");
            membro(c, ALVO_ADMIN, CLUBE_A, "ADMIN");
            membro(c, ALVO_ADMIN2, CLUBE_A, "ADMIN");
            membro(c, ALVO_DIRECAO, CLUBE_A, "DIRECAO", "ATLETA");
            membro(c, ALVO_SIMPLES, CLUBE_A, "TREINADOR");

            membro(c, ADMIN_B, CLUBE_B, "ADMIN");
            membro(c, SO_B, CLUBE_B, "ATLETA");

            membro(c, RACE_C_ACTOR, CLUBE_C, "ADMIN");
            membro(c, RACE_C_UNICO, CLUBE_C, "ADMIN");
            membro(c, RACE_D_ACTOR, CLUBE_D, "ADMIN");
            membro(c, RACE_D_UNICO, CLUBE_D, "ADMIN");

            ativar(c, CLUBE_C, RACE_C_ACTOR);
            ativar(c, CLUBE_D, RACE_D_ACTOR);
        }
    }

    private static void clube(Connection c, UUID id, String nome, String slug) throws SQLException {
        executar(c, "INSERT INTO tenant (id, name, slug) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                id, nome, slug);
    }

    private void membro(Connection c, String email, UUID tenant, String... roles) throws SQLException {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASS);
        }
        executar(c, "INSERT INTO app_user (id, email, name, password_hash) VALUES (?, ?, 'Teste', ?) "
                + "ON CONFLICT DO NOTHING", id(email), email, passwordHash);
        for (String role : roles) {
            executar(c, "INSERT INTO membership (tenant_id, user_id, role) VALUES (?, ?, ?) "
                    + "ON CONFLICT DO NOTHING", tenant, id(email), role);
        }
    }

    private static void ativar(Connection c, UUID tenant, String email) throws SQLException {
        executar(c, "UPDATE membership SET status = 'ACTIVE', updated_at = now() "
                + "WHERE tenant_id = ? AND user_id = ?", tenant, id(email));
    }

    private static void desativarMembershipComoSuperuser(UUID tenant, String email) throws SQLException {
        try (Connection c = superuser()) {
            executar(c, "UPDATE membership SET status = 'DISABLED', updated_at = now() "
                    + "WHERE tenant_id = ? AND user_id = ?", tenant, id(email));
        }
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
