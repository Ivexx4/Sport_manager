package pt.voleibol.gestao.identity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import pt.voleibol.gestao.shared.FlowTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Limitação de tentativas de login por clube + IP, contra PostgreSQL real.
 *
 * <p>Cada teste usa um IP diferente para não depender da ordem de execução: o limitador é um bean
 * único do contexto e guarda o estado entre pedidos.
 */
@SpringBootTest(properties = {
        "app.security.login-rate-limit.max-attempts=3",
        "app.security.login-rate-limit.block-duration=PT2S"
})
class LoginRateLimitFlowTest extends FlowTestSupport {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registarPropriedades(registry);
    }

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000cccc");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000dddd");
    static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    static final String HOST_A = "clube-rate-a.localhost";
    static final String HOST_B = "clube-rate-b.localhost";
    static final String EMAIL_ANA = "ana@rate-a.test";
    static final String EMAIL_BRUNO = "bruno@rate-b.test";
    static final String PASSWORD_ANA = "segredo-ana";
    static final String PASSWORD_BRUNO = "segredo-bruno";

    @BeforeEach
    void preparar() throws SQLException {
        semear();
    }

    @Test
    void aposTresFalhasOBloqueioDevolve429MesmoComPasswordCorreta() throws Exception {
        String ip = "10.10.0.1";
        falhar(HOST_A, ip, EMAIL_ANA, 3);

        login(HOST_A, ip, EMAIL_ANA, PASSWORD_ANA)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().string(""));
    }

    @Test
    void oBloqueioDeUmClubeNaoAfetaOutroClubeNoMesmoIp() throws Exception {
        String ip = "10.10.0.2";
        falhar(HOST_A, ip, EMAIL_ANA, 3);

        login(HOST_B, ip, EMAIL_BRUNO, PASSWORD_BRUNO).andExpect(status().isOk());
    }

    @Test
    void oBloqueioDeUmIpNaoAfetaOutroIpNoMesmoClube() throws Exception {
        falhar(HOST_A, "10.10.0.3", EMAIL_ANA, 3);

        login(HOST_A, "10.10.0.4", EMAIL_ANA, PASSWORD_ANA).andExpect(status().isOk());
    }

    @Test
    void loginComSucessoLimpaOContador() throws Exception {
        String ip = "10.10.0.5";
        falhar(HOST_A, ip, EMAIL_ANA, 2);
        login(HOST_A, ip, EMAIL_ANA, PASSWORD_ANA).andExpect(status().isOk());

        falhar(HOST_A, ip, EMAIL_ANA, 2);
        login(HOST_A, ip, EMAIL_ANA, PASSWORD_ANA).andExpect(status().isOk());
    }

    @Test
    void xForwardedForNaoEUsadoPorOmissao() throws Exception {
        String ip = "10.10.0.6";
        for (int i = 0; i < 3; i++) {
            loginComEncaminhamento(HOST_A, ip, "198.51.100." + i, EMAIL_ANA, "errada")
                    .andExpect(status().isUnauthorized());
        }

        loginComEncaminhamento(HOST_A, ip, "198.51.100.99", EMAIL_ANA, PASSWORD_ANA)
                .andExpect(status().isTooManyRequests());
    }

    private void falhar(String host, String ip, String email, int vezes) throws Exception {
        for (int i = 0; i < vezes; i++) {
            login(host, ip, email, "password-errada").andExpect(status().isUnauthorized());
        }
    }

    private ResultActions login(String host, String ip, String email, String password) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(endereco(host, ip));
        return mvc.perform(request);
    }

    private ResultActions loginComEncaminhamento(String host, String ip, String forwardedFor,
                                                 String email, String password) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .header("X-Forwarded-For", forwardedFor)
                .with(endereco(host, ip));
        return mvc.perform(request);
    }

    private static RequestPostProcessor endereco(String serverName, String remoteAddr) {
        return request -> {
            request.setServerName(serverName);
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }

    /** Dados da plataforma e dos clubes: inseridos com o superuser do contentor (ignora RLS). */
    private void semear() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            executar(c, "INSERT INTO tenant (id, name, slug) VALUES (?, 'Clube Rate A', 'clube-rate-a') "
                    + "ON CONFLICT DO NOTHING", CLUBE_A);
            executar(c, "INSERT INTO tenant (id, name, slug) VALUES (?, 'Clube Rate B', 'clube-rate-b') "
                    + "ON CONFLICT DO NOTHING", CLUBE_B);
            utilizador(c, ANA, EMAIL_ANA, PASSWORD_ANA);
            utilizador(c, BRUNO, EMAIL_BRUNO, PASSWORD_BRUNO);
            membership(c, CLUBE_A, ANA);
            membership(c, CLUBE_B, BRUNO);
        }
    }

    private void utilizador(Connection c, UUID id, String email, String password) throws SQLException {
        executar(c, "INSERT INTO app_user (id, email, name, password_hash) VALUES (?, ?, 'Teste', ?) "
                + "ON CONFLICT DO NOTHING", id, email, passwordEncoder.encode(password));
    }

    private static void membership(Connection c, UUID tenant, UUID user) throws SQLException {
        executar(c, "INSERT INTO membership (tenant_id, user_id, role, status) VALUES (?, ?, 'ADMIN', 'ACTIVE') "
                + "ON CONFLICT DO NOTHING", tenant, user);
    }

}
