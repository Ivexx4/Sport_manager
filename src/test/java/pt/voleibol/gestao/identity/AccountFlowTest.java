package pt.voleibol.gestao.identity;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

import pt.voleibol.gestao.notifications.EmailSender;
import pt.voleibol.gestao.tenancy.TenantFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Convites, aceitação, recuperação e alteração de password, contra PostgreSQL real.
 * Os e-mails são capturados em memória; os tokens saem do link da mensagem, como na vida real.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
@Import(AccountFlowTest.CapturedEmails.class)
class AccountFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    record Sent(String to, String subject, String body) {
    }

    static final Queue<Sent> SENT = new ConcurrentLinkedQueue<>();

    @TestConfiguration
    static class CapturedEmails {
        @Bean
        @Primary
        EmailSender capturingEmailSender() {
            return (to, subject, body) -> SENT.add(new Sent(to, subject, body));
        }
    }

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");
    static final String HOST_A = "clube-a.localhost";
    static final String HOST_B = "clube-b.localhost";

    static final String ADMIN_A = "admin@clube-a.test";
    static final String DIRECAO_A = "direcao@clube-a.test";
    static final String TREINADOR_A = "treinador@clube-a.test";
    static final String ADMIN_B = "admin@clube-b.test";
    static final String SO_CLUBE_A = "so-clube-a@clube-a.test";
    static final String PASS = "palavra-passe-de-teste";

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

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

    // --- convites ---

    @Test
    void administradorConvidaEPessoaNovaAceitaEEntra() throws Exception {
        String email = novoEmail();
        String admin = acesso(HOST_A, ADMIN_A, PASS);

        convidar(HOST_A, admin, email, "Novo Treinador", "TREINADOR").andExpect(status().isAccepted());
        String token = tokenDoUltimoEmailPara(email);

        aceitar(HOST_A, token, "Nome Escolhido", "uma-password-comprida").andExpect(status().isNoContent());

        String novo = acesso(HOST_A, email, "uma-password-comprida");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + novo).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("TREINADOR"));
    }

    @Test
    void conviteSoServeUmaVezESoNoClubeDeOrigem() throws Exception {
        String email = novoEmail();
        convidar(HOST_A, acesso(HOST_A, ADMIN_A, PASS), email, "Pessoa", "ATLETA").andExpect(status().isAccepted());
        String token = tokenDoUltimoEmailPara(email);

        aceitar(HOST_B, token, "Pessoa", "uma-password-comprida").andExpect(status().isBadRequest());
        aceitar(HOST_A, token, "Pessoa", "uma-password-comprida").andExpect(status().isNoContent());
        aceitar(HOST_A, token, "Pessoa", "uma-password-comprida").andExpect(status().isBadRequest());
    }

    @Test
    void passwordFracaNaoGastaOConvite() throws Exception {
        String email = novoEmail();
        convidar(HOST_A, acesso(HOST_A, ADMIN_A, PASS), email, "Pessoa", "ATLETA").andExpect(status().isAccepted());
        String token = tokenDoUltimoEmailPara(email);

        aceitar(HOST_A, token, "Pessoa", "curta").andExpect(status().isBadRequest());
        aceitar(HOST_A, token, "Pessoa", null).andExpect(status().isBadRequest());
        aceitar(HOST_A, token, "Pessoa", "uma-password-comprida").andExpect(status().isNoContent());
    }

    @Test
    void conviteExpiradoOuSubstituidoNaoServe() throws Exception {
        String email = novoEmail();
        String admin = acesso(HOST_A, ADMIN_A, PASS);

        convidar(HOST_A, admin, email, "Pessoa", "ATLETA").andExpect(status().isAccepted());
        String primeiro = tokenDoUltimoEmailPara(email);
        convidar(HOST_A, admin, email, "Pessoa", "ATLETA").andExpect(status().isAccepted());
        String segundo = tokenDoUltimoEmailPara(email);
        assertThat(segundo).isNotEqualTo(primeiro);

        aceitar(HOST_A, primeiro, "Pessoa", "uma-password-comprida").andExpect(status().isBadRequest());

        executarComoSuperuser("UPDATE invitation SET expires_at = now() - interval '1 hour' "
                + "WHERE tenant_id = ? AND lower(email) = lower(?)", CLUBE_A, email);
        aceitar(HOST_A, segundo, "Pessoa", "uma-password-comprida").andExpect(status().isBadRequest());
    }

    @Test
    void quemJaTemContaAceitaNoutroClubeSemNovaPassword() throws Exception {
        String adminB = acesso(HOST_B, ADMIN_B, PASS);
        convidar(HOST_B, adminB, SO_CLUBE_A, "Já Existe", "FINANCEIRO").andExpect(status().isAccepted());
        String token = tokenDoUltimoEmailPara(SO_CLUBE_A);

        aceitar(HOST_B, token, null, null).andExpect(status().isNoContent());

        // A password da conta mantém-se: entra no clube B com a mesma de sempre.
        String novo = acesso(HOST_B, SO_CLUBE_A, PASS);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + novo).with(host(HOST_B)))
                .andExpect(jsonPath("$.roles[0]").value("FINANCEIRO"));
    }

    @Test
    void soAdminEDirecaoConvidamEDirecaoNaoAtribuiPapeisDeGestao() throws Exception {
        convidar(HOST_A, acesso(HOST_A, TREINADOR_A, PASS), novoEmail(), "X", "ATLETA")
                .andExpect(status().isForbidden());

        String direcao = acesso(HOST_A, DIRECAO_A, PASS);
        convidar(HOST_A, direcao, novoEmail(), "X", "ADMIN").andExpect(status().isForbidden());
        convidar(HOST_A, direcao, novoEmail(), "X", "DIRECAO").andExpect(status().isForbidden());
        convidar(HOST_A, direcao, novoEmail(), "X", "ATLETA").andExpect(status().isAccepted());

        convidar(HOST_A, acesso(HOST_A, ADMIN_A, PASS), novoEmail(), "X", "DIRECAO")
                .andExpect(status().isAccepted());
    }

    @Test
    void convidarSemSessaoOuComPapelInexistenteFalha() throws Exception {
        mvc.perform(post("/api/v1/users/invitations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.test\",\"name\":\"X\",\"roles\":[\"ATLETA\"]}").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());

        convidar(HOST_A, acesso(HOST_A, ADMIN_A, PASS), novoEmail(), "X", "SUPERUTILIZADOR")
                .andExpect(status().isBadRequest());
    }

    // --- recuperação de password ---

    @Test
    void recuperacaoRepoeAPasswordEInvalidaAsSessoesAnteriores() throws Exception {
        String email = "reset@clube-a.test";
        String antiga = "password-antiga-reset";
        String nova = "password-nova-reset-1";
        String refreshAntigo = refreshDe(login(HOST_A, email, antiga));

        esqueci(HOST_A, email).andExpect(status().isAccepted());
        String token = tokenDoUltimoEmailPara(email);

        reporPassword(HOST_A, token, "curta").andExpect(status().isBadRequest());   // não gasta o token
        reporPassword(HOST_A, token, nova).andExpect(status().isNoContent());
        reporPassword(HOST_A, token, "outra-password-longa").andExpect(status().isBadRequest());   // uma só vez

        login(HOST_A, email, antiga).andExpect(status().isUnauthorized());
        login(HOST_A, email, nova).andExpect(status().isOk());
        refresh(HOST_A, refreshAntigo).andExpect(status().isUnauthorized());
    }

    @Test
    void recuperacaoNaoRevelaSeAContaExiste() throws Exception {
        int antes = SENT.size();

        esqueci(HOST_A, "ninguem@clube-a.test").andExpect(status().isAccepted());
        // existe, mas não pertence ao clube B
        esqueci(HOST_B, ADMIN_A).andExpect(status().isAccepted());

        assertThat(SENT.size()).isEqualTo(antes);
    }

    @Test
    void recuperacaoTemLimiteDePedidosPorHora() throws Exception {
        String email = "limite@clube-a.test";
        int antes = SENT.size();

        for (int i = 0; i < 5; i++) {
            esqueci(HOST_A, email).andExpect(status().isAccepted());
        }

        assertThat(SENT.size() - antes).isEqualTo(3);
    }

    @Test
    void tokenDeRecuperacaoNaoServeNoutroClube() throws Exception {
        String email = "reset-clube@clube-a.test";
        esqueci(HOST_A, email).andExpect(status().isAccepted());
        String token = tokenDoUltimoEmailPara(email);

        reporPassword(HOST_B, token, "password-nova-reset-2").andExpect(status().isBadRequest());
    }

    // --- alteração de password ---

    @Test
    void utilizadorAutenticadoAlteraAPassword() throws Exception {
        String email = "muda@clube-a.test";
        String atual = "password-atual-muda";
        String nova = "password-nova-muda-1";
        String token = acesso(HOST_A, email, atual);

        alterar(HOST_A, token, "errada-errada", nova).andExpect(status().isBadRequest());
        alterar(HOST_A, token, atual, "curta").andExpect(status().isBadRequest());
        alterar(HOST_A, token, atual, atual).andExpect(status().isBadRequest());
        alterar(HOST_A, token, atual, nova).andExpect(status().isNoContent());

        login(HOST_A, email, atual).andExpect(status().isUnauthorized());
        login(HOST_A, email, nova).andExpect(status().isOk());
    }

    @Test
    void alterarPasswordExigeSessao() throws Exception {
        mvc.perform(post("/api/v1/auth/password/change").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"yyyyyyyyyyyy\"}").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
    }

    // --- auxiliares ---

    private ResultActions convidar(String host, String token, String email, String nome, String papel)
            throws Exception {
        return mvc.perform(post("/api/v1/users/invitations").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content("{\"email\":\"" + email + "\",\"name\":\"" + nome + "\",\"roles\":[\"" + papel + "\"]}")
                .with(host(host)));
    }

    private ResultActions aceitar(String host, String token, String nome, String password) throws Exception {
        String corpo = "{\"token\":\"" + token + "\""
                + (nome == null ? "" : ",\"name\":\"" + nome + "\"")
                + (password == null ? "" : ",\"password\":\"" + password + "\"") + "}";
        return mvc.perform(post("/api/v1/auth/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content(corpo).with(host(host)));
    }

    private ResultActions esqueci(String host, String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}").with(host(host)));
    }

    private ResultActions reporPassword(String host, String token, String nova) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + nova + "\"}").with(host(host)));
    }

    private ResultActions alterar(String host, String accessToken, String atual, String nova) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/change").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content("{\"currentPassword\":\"" + atual + "\",\"newPassword\":\"" + nova + "\"}")
                .with(host(host)));
    }

    private ResultActions login(String host, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").with(host(host)));
    }

    private ResultActions refresh(String host, String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}").with(host(host)));
    }

    private String acesso(String host, String email, String password) throws Exception {
        return JsonPath.read(login(host, email, password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private static String refreshDe(ResultActions login) throws Exception {
        return JsonPath.read(login.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.refreshToken");
    }

    private static String tokenDoUltimoEmailPara(String email) {
        Sent ultimo = null;
        for (Sent sent : SENT) {
            if (sent.to().equalsIgnoreCase(email)) {
                ultimo = sent;
            }
        }
        assertThat(ultimo).as("e-mail enviado para " + email).isNotNull();
        Matcher matcher = TOKEN.matcher(ultimo.body());
        assertThat(matcher.find()).as("link com token no e-mail").isTrue();
        return matcher.group(1);
    }

    private static String novoEmail() {
        return "novo-" + UUID.randomUUID() + "@clube-a.test";
    }

    private static RequestPostProcessor host(String serverName) {
        return request -> {
            request.setServerName(serverName);
            return request;
        };
    }

    /** Dados de base inseridos com o superuser do contentor (ignora RLS). Idempotente. */
    private void semear() throws SQLException {
        try (Connection c = superuser()) {
            executar(c, "INSERT INTO tenant (id, name, slug) VALUES (?, 'Clube A', 'clube-a') ON CONFLICT DO NOTHING",
                    CLUBE_A);
            executar(c, "INSERT INTO tenant (id, name, slug) VALUES (?, 'Clube B', 'clube-b') ON CONFLICT DO NOTHING",
                    CLUBE_B);

            membro(c, ADMIN_A, PASS, CLUBE_A, "ADMIN");
            membro(c, DIRECAO_A, PASS, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR_A, PASS, CLUBE_A, "TREINADOR");
            membro(c, ADMIN_B, PASS, CLUBE_B, "ADMIN");
            membro(c, SO_CLUBE_A, PASS, CLUBE_A, "TREINADOR");
            // Contas que cada teste altera: nenhum outro teste as usa.
            membro(c, "reset@clube-a.test", "password-antiga-reset", CLUBE_A, "ATLETA");
            membro(c, "reset-clube@clube-a.test", PASS, CLUBE_A, "ATLETA");
            membro(c, "limite@clube-a.test", PASS, CLUBE_A, "ATLETA");
            membro(c, "muda@clube-a.test", "password-atual-muda", CLUBE_A, "ATLETA");
        }
    }

    private void membro(Connection c, String email, String password, UUID tenant, String papel)
            throws SQLException {
        UUID userId = UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8));
        executar(c, "INSERT INTO app_user (id, email, name, password_hash) VALUES (?, ?, 'Teste', ?) "
                + "ON CONFLICT DO NOTHING", userId, email, passwordEncoder.encode(password));
        executar(c, "INSERT INTO membership (tenant_id, user_id, role) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                tenant, userId, papel);
    }

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void executarComoSuperuser(String sql, Object... params) throws SQLException {
        try (Connection c = superuser()) {
            executar(c, sql, params);
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
