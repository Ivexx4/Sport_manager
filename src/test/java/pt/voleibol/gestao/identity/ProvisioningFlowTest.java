package pt.voleibol.gestao.identity;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pt.voleibol.gestao.shared.FlowTestSupport;
import pt.voleibol.gestao.shared.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
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
        "app.security.login-rate-limit.enabled=false",
        "app.security.mfa.require-for-admins=false"
})
class ProvisioningFlowTest extends FlowTestSupport {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        BASE = TestDatabase.base(ProvisioningFlowTest.class);
        registarPropriedades(registry);
    }

    static final String BOOTSTRAP_KEY = "chave-de-teste";

    static final UUID CLUBE_ORIGEM = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    static final String EXISTENTE = "existente@provision.test";
    static final String PASSWORD_EXISTENTE = "password-existente";

    @BeforeEach
    void preparar() throws SQLException {
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

    private static String slugUnico(String prefixo) {
        return prefixo + "-" + UUID.randomUUID().toString().substring(0, 8);
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

}
