package pt.voleibol.gestao.identity;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Locale;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import pt.voleibol.gestao.tenancy.TenantFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Provisionamento de clubes ({@code POST /api/v1/platform/tenants}) contra PostgreSQL real.
 *
 * <p>Valida a chave de bootstrap, a validação do pedido, o conflito de slug, a criação do clube com o
 * primeiro {@code ADMIN} (incluindo a conta já existente noutro clube) e o login no clube criado.
 */
@SpringBootTest(properties = {
        "app.platform.bootstrap-key=chave-de-teste",
        "app.security.login-rate-limit.enabled=false"
})
@Testcontainers
class ProvisioningFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final String BOOTSTRAP_KEY = "chave-de-teste";

    static final UUID CLUBE_ORIGEM = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    static final String EXISTENTE = "existente@provision.test";
    static final String PASSWORD_EXISTENTE = "password-existente";

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

    // --- criação ---

    @Test
    void provisionaClubeComPrimeiroAdminQueEntraLogo() throws Exception {
        String slug = slugUnico("novo");
        String email = "admin-" + slug + "@provision.test";
        String password = "password-admin-1";
        String nome = "Clube " + slug;

        MvcResult criado = provisionar(BOOTSTRAP_KEY, nome, slug, email, "Admin Novo", password)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.name").value(nome))
                .andExpect(jsonPath("$.adminEmail").value(email))
                .andReturn();

        String corpo = criado.getResponse().getContentAsString();
        String tenantId = JsonPath.read(corpo, "$.tenantId");
        String adminUserId = JsonPath.read(corpo, "$.adminUserId");
        assertThat(tenantId).isNotBlank();
        assertThat(adminUserId).isNotBlank();

        String host = slug + ".localhost";
        String token = acesso(host, email, password);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token).with(host(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));

        assertThat(contar("SELECT count(*) FROM tenant WHERE slug = ?", slug)).isEqualTo(1);
        assertThat(contar("""
                SELECT count(*) FROM membership
                WHERE tenant_id = ? AND user_id = ? AND role = 'ADMIN' AND status = 'ACTIVE'
                """, UUID.fromString(tenantId), UUID.fromString(adminUserId))).isEqualTo(1);
    }

    @Test
    void emailJaExistenteAssociaAContaSemTrocarAPassword() throws Exception {
        String slug = slugUnico("existente");
        String emailMaiusculas = EXISTENTE.toUpperCase(Locale.ROOT);

        MvcResult criado = provisionar(BOOTSTRAP_KEY, "Clube Existente", slug, emailMaiusculas,
                "Nome Atualizado", "password-nova-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adminEmail").value(EXISTENTE))
                .andReturn();
        String adminUserId = JsonPath.read(criado.getResponse().getContentAsString(), "$.adminUserId");

        // É a mesma conta (email é único em todos os clubes) e a password antiga mantém-se.
        assertThat(UUID.fromString(adminUserId)).isEqualTo(id(EXISTENTE));
        String host = slug + ".localhost";
        String token = acesso(host, EXISTENTE, PASSWORD_EXISTENTE);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token).with(host(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
        login(host, EXISTENTE, "password-nova-1").andExpect(status().isUnauthorized());

        assertThat(contar("SELECT count(*) FROM app_user WHERE id = ? AND name = 'Nome Atualizado'",
                id(EXISTENTE))).isEqualTo(1);
        // A relação original com o outro clube não é alterada.
        assertThat(contar("""
                SELECT count(*) FROM membership
                WHERE tenant_id = ? AND user_id = ? AND role = 'TREINADOR' AND status = 'ACTIVE'
                """, CLUBE_ORIGEM, id(EXISTENTE))).isEqualTo(1);
    }

    // --- autorização e conflitos ---

    @Test
    void chaveEmFaltaOuErradaDa403ENaoCriaClube() throws Exception {
        String slug = slugUnico("sem-chave");
        String email = "admin-" + slug + "@provision.test";

        mvc.perform(pedido("Clube Sem Chave", slug, email, "Admin", "password-admin-1"))
                .andExpect(status().isForbidden());
        provisionar("chave-errada", "Clube Sem Chave", slug, email, "Admin", "password-admin-1")
                .andExpect(status().isForbidden());

        assertThat(contar("SELECT count(*) FROM tenant WHERE slug = ?", slug)).isZero();
    }

    @Test
    void slugRepetidoDa409ENaoCriaSegundoClube() throws Exception {
        String slug = slugUnico("repetido");
        provisionar(BOOTSTRAP_KEY, "Clube Original", slug, "original-" + slug + "@provision.test",
                "Admin", "password-admin-1")
                .andExpect(status().isCreated());

        provisionar(BOOTSTRAP_KEY, "Clube Duplicado", slug, "duplicado-" + slug + "@provision.test",
                "Admin", "password-admin-1")
                .andExpect(status().isConflict());

        assertThat(contar("SELECT count(*) FROM tenant WHERE slug = ?", slug)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM app_user WHERE email = ?",
                "duplicado-" + slug + "@provision.test")).isZero();
    }

    @Test
    void pedidoInvalidoDa400ENaoCriaClube() throws Exception {
        provisionar(BOOTSTRAP_KEY, "Clube", "Slug_Invalido", "a-" + UUID.randomUUID() + "@provision.test",
                "Admin", "password-admin-1")
                .andExpect(status().isBadRequest());

        String slug = slugUnico("password-curta");
        provisionar(BOOTSTRAP_KEY, "Clube", slug, "curta-" + slug + "@provision.test",
                "Admin", "curta")
                .andExpect(status().isBadRequest());

        String slugSemNome = slugUnico("sem-nome");
        provisionar(BOOTSTRAP_KEY, " ", slugSemNome, "sem-nome-" + slugSemNome + "@provision.test",
                "Admin", "password-admin-1")
                .andExpect(status().isBadRequest());

        provisionar(BOOTSTRAP_KEY, "Clube", slugUnico("email-mau"), "nao-e-um-email",
                "Admin", "password-admin-1")
                .andExpect(status().isBadRequest());

        assertThat(contar("SELECT count(*) FROM tenant WHERE slug LIKE 'password-curta-%'"
                + " OR slug LIKE 'sem-nome-%' OR slug LIKE 'email-mau-%'")).isZero();
    }

    // --- auxiliares ---

    private ResultActions provisionar(String chave, String nome, String slug, String email,
                                      String adminNome, String password) throws Exception {
        return mvc.perform(pedido(nome, slug, email, adminNome, password).header("X-Bootstrap-Key", chave));
    }

    private static MockHttpServletRequestBuilder pedido(String nome, String slug, String email,
                                                        String adminNome, String password) {
        String corpo = """
                {"name":"%s","slug":"%s","adminEmail":"%s","adminName":"%s","adminPassword":"%s"}
                """.formatted(nome, slug, email, adminNome, password);
        return post("/api/v1/platform/tenants").contentType(MediaType.APPLICATION_JSON).content(corpo);
    }

    private ResultActions login(String host, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(host(host)));
    }

    private String acesso(String host, String email, String password) throws Exception {
        return JsonPath.read(login(host, email, password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private static RequestPostProcessor host(String serverName) {
        return request -> {
            request.setServerName(serverName);
            return request;
        };
    }

    private static String slugUnico(String prefixo) {
        return prefixo + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static UUID id(String email) {
        return UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8));
    }

    /** Clube de origem e conta já existente, para testar a associação por email. */
    private void semear() throws SQLException {
        try (Connection c = superuser()) {
            executar(c, "INSERT INTO tenant (id, name, slug) VALUES (?, 'Clube Origem', 'origem-provision') "
                    + "ON CONFLICT DO NOTHING", CLUBE_ORIGEM);
            executar(c, "INSERT INTO app_user (id, email, name, password_hash) VALUES (?, ?, 'Existente', ?) "
                    + "ON CONFLICT DO NOTHING", id(EXISTENTE), EXISTENTE,
                    passwordEncoder.encode(PASSWORD_EXISTENTE));
            executar(c, "INSERT INTO membership (tenant_id, user_id, role) VALUES (?, ?, 'TREINADOR') "
                    + "ON CONFLICT DO NOTHING", CLUBE_ORIGEM, id(EXISTENTE));
        }
    }

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

    private static void executar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
