package pt.voleibol.gestao.identity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import pt.voleibol.gestao.shared.FlowTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo completo de autenticação contra PostgreSQL real, com o filtro de tenant e a cadeia de segurança
 * montados como em produção. O tenant escolhe-se pelo nome do servidor (subdomínio).
 */
class AuthFlowTest extends FlowTestSupport {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registarPropriedades(registry);
    }

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");
    static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    static final UUID CARLA = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    static final String HOST_A = "clube-a.localhost";
    static final String HOST_B = "clube-b.localhost";

    @BeforeEach
    void preparar() throws SQLException {
        // O filtro de tenant tem de vir antes da cadeia do Spring Security, como em produção.
        semear();
    }

    // --- login e /me ---

    @Test
    void loginDaTokensEMeMostraOsPapeis() throws Exception {
        String token = acessoDe(login(HOST_A, "ana@clube-a.test", "segredo-ana"));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ANA.toString()))
                .andExpect(jsonPath("$.tenantId").value(CLUBE_A.toString()))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void mesmoUtilizadorTemPapeisDiferentesEmCadaClube() throws Exception {
        String tokenA = acessoDe(login(HOST_A, "bruno@clubes.test", "segredo-bruno"));
        String tokenB = acessoDe(login(HOST_B, "bruno@clubes.test", "segredo-bruno"));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenA).with(host(HOST_A)))
                .andExpect(jsonPath("$.roles[0]").value("TREINADOR"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenB).with(host(HOST_B)))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void passwordErradaEEmailDesconhecidoDaoARespostaIgual() throws Exception {
        login(HOST_A, "ana@clube-a.test", "password-errada")
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        login(HOST_A, "ninguem@clube-a.test", "qualquer")
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void utilizadorSemPapelNoClubeNaoEntra() throws Exception {
        // A Ana só pertence ao clube A.
        login(HOST_B, "ana@clube-a.test", "segredo-ana").andExpect(status().isUnauthorized());
    }

    @Test
    void membershipDesativadaNaoEntra() throws Exception {
        login(HOST_A, "carla@clube-a.test", "segredo-carla").andExpect(status().isUnauthorized());
    }

    @Test
    void loginSemSubdominioDaPedidoInvalido() throws Exception {
        login("localhost", "ana@clube-a.test", "segredo-ana").andExpect(status().isBadRequest());
    }

    @Test
    void clubeDesconhecidoDaNaoEncontrado() throws Exception {
        login("clube-inexistente.localhost", "ana@clube-a.test", "segredo-ana")
                .andExpect(status().isNotFound());
    }

    // --- validação do token ---

    @Test
    void tokenDeUmClubeNaoServeNoutro() throws Exception {
        String token = acessoDe(login(HOST_A, "ana@clube-a.test", "segredo-ana"));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token).with(host(HOST_B)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void semTokenOuComTokenAdulteradoDa401() throws Exception {
        mvc.perform(get("/api/v1/auth/me").with(host(HOST_A))).andExpect(status().isUnauthorized());

        String token = acessoDe(login(HOST_A, "ana@clube-a.test", "segredo-ana"));
        String[] partes = token.split("\\.");
        char primeira = partes[2].charAt(0);
        String assinaturaAlterada = (primeira == 'A' ? 'B' : 'A') + partes[2].substring(1);
        String adulterado = partes[0] + "." + partes[1] + "." + assinaturaAlterada;

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + adulterado).with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
    }

    // --- refresh e logout ---

    @Test
    void refreshRodaOsTokensEReutilizarUmJaUsadoRevogaTudo() throws Exception {
        String refreshInicial = refreshDe(login(HOST_A, "ana@clube-a.test", "segredo-ana"));

        String corpo = refresh(HOST_A, refreshInicial).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String refreshNovo = JsonPath.read(corpo, "$.refreshToken");
        assertThat(refreshNovo).isNotEqualTo(refreshInicial);

        // Reutilização do token antigo: recusada e a sessão inteira é revogada.
        refresh(HOST_A, refreshInicial).andExpect(status().isUnauthorized());
        refresh(HOST_A, refreshNovo).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevogaORefreshToken() throws Exception {
        String refresh = refreshDe(login(HOST_A, "ana@clube-a.test", "segredo-ana"));

        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}").with(host(HOST_A)))
                .andExpect(status().isNoContent());

        refresh(HOST_A, refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenNaoServeNoutroClube() throws Exception {
        String refresh = refreshDe(login(HOST_A, "bruno@clubes.test", "segredo-bruno"));

        refresh(HOST_B, refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void tentativasFicamNaAuditoria() throws Exception {
        login(HOST_A, "ana@clube-a.test", "segredo-ana").andExpect(status().isOk());
        login(HOST_A, "ana@clube-a.test", "errada").andExpect(status().isUnauthorized());

        assertThat(contarAuditoria("LOGIN_SUCCESS")).isPositive();
        assertThat(contarAuditoria("LOGIN_FAILED")).isPositive();
    }

    // --- auxiliares ---

    private ResultActions login(String host, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(host(host)));
    }

    private ResultActions refresh(String host, String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}").with(host(host)));
    }

    private static String acessoDe(ResultActions login) throws Exception {
        return JsonPath.read(login.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.accessToken");
    }

    private static String refreshDe(ResultActions login) throws Exception {
        return JsonPath.read(login.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.refreshToken");
    }

    /** Dados da plataforma e de vários clubes: inseridos com o superuser do contentor (ignora RLS). */
    private void semear() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            executar(c, "INSERT INTO tenant (id, name, slug) VALUES (?, 'Clube A', 'clube-a') ON CONFLICT DO NOTHING",
                    CLUBE_A);
            executar(c, "INSERT INTO tenant (id, name, slug) VALUES (?, 'Clube B', 'clube-b') ON CONFLICT DO NOTHING",
                    CLUBE_B);
            utilizador(c, ANA, "ana@clube-a.test", "segredo-ana");
            utilizador(c, BRUNO, "bruno@clubes.test", "segredo-bruno");
            utilizador(c, CARLA, "carla@clube-a.test", "segredo-carla");
            membership(c, CLUBE_A, ANA, "ADMIN", "ACTIVE");
            membership(c, CLUBE_A, BRUNO, "TREINADOR", "ACTIVE");
            membership(c, CLUBE_B, BRUNO, "ADMIN", "ACTIVE");
            membership(c, CLUBE_A, CARLA, "TREINADOR", "DISABLED");
        }
    }

    private void utilizador(Connection c, UUID id, String email, String password) throws SQLException {
        executar(c, "INSERT INTO app_user (id, email, name, password_hash) VALUES (?, ?, 'Teste', ?) "
                + "ON CONFLICT DO NOTHING", id, email, passwordEncoder.encode(password));
    }

    private static void membership(Connection c, UUID tenant, UUID user, String role, String status)
            throws SQLException {
        executar(c, "INSERT INTO membership (tenant_id, user_id, role, status) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING", tenant, user, role, status);
    }

}
