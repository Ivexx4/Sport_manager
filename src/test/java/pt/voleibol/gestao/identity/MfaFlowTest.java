package pt.voleibol.gestao.identity;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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

import pt.voleibol.gestao.shared.FlowTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MFA por TOTP contra PostgreSQL real, com o MFA obrigatório para {@code ADMIN}.
 *
 * <p>Cobre a inscrição forçada no login, a recusa do desafio como token de acesso, a verificação com
 * proteção de repetição, os códigos de recuperação de uso único, a ativação opcional e a desativação
 * com password. Os códigos são calculados com o mesmo {@link Totp}, sem depender de um autenticador.
 */
@SpringBootTest(properties = {
        "app.security.login-rate-limit.enabled=false",
        "app.security.mfa.require-for-admins=true"
})
class MfaFlowTest extends FlowTestSupport {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registarPropriedades(registry);
    }

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000e0a0");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000e0b0");
    static final String HOST_A = "clube-mfa.localhost";
    static final String HOST_B = "clube-mfa-b.localhost";
    static final String ADMIN = "admin@mfa.test";
    static final String TREINADOR = "treinador@mfa.test";

    /** Formato de código de recuperação, mas que nunca é gerado: serve para falhar de forma previsível. */
    static final String CODIGO_INVALIDO = "ZZZZZ-ZZZZZ";

    record Inscricao(String secret, List<String> recoveryCodes) {
    }

    @BeforeEach
    void preparar() throws SQLException {
        try (Connection c = superuser()) {
            clube(c, CLUBE_A, "Clube MFA", "clube-mfa");
            clube(c, CLUBE_B, "Clube MFA B", "clube-mfa-b");
            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            // Cada teste começa com o MFA desligado nestas contas.
            desligar(c, id(ADMIN));
            desligar(c, id(TREINADOR));
        }
    }

    // --- inscrição obrigatória ---

    @Test
    void adminTemDeInscreverOMfaAntesDeEntrar() throws Exception {
        String challenge = desafioDeInscricao(HOST_A, ADMIN);

        String setup = setup(HOST_A, challenge);
        String secret = JsonPath.read(setup, "$.secret");
        assertThat(secret).hasSize(32);
        assertThat(JsonPath.<String>read(setup, "$.otpauthUri"))
                .startsWith("otpauth://totp/")
                .contains("secret=" + secret, "digits=6", "period=30");

        MvcResult confirmado = confirmarInscricao(HOST_A, challenge, codigo(secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn();
        String token = JsonPath.read(confirmado.getResponse().getContentAsString(), "$.accessToken");

        mvc.perform(get("/api/v1/auth/mfa").header("Authorization", "Bearer " + token).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.pending").value(false))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(10));
    }

    @Test
    void tokenDeDesafioNaoAutenticaPedidos() throws Exception {
        String challenge = desafioDeInscricao(HOST_A, ADMIN);

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + challenge).with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void desafioDeUmClubeNaoServeNoutro() throws Exception {
        String challenge = desafioDeInscricao(HOST_A, ADMIN);

        confirmarInscricao(HOST_B, challenge, CODIGO_INVALIDO).andExpect(status().isUnauthorized());
    }

    // --- login com segundo fator ---

    @Test
    void loginComMfaAtivoPedeCodigoENaoAceitaRepeticao() throws Exception {
        String secret = inscrever().secret();
        String challenge = desafioDeVerificacao(HOST_A, ADMIN);
        String code = codigoDaJanelaSeguinte(secret);

        tokensDe(verify(HOST_A, challenge, code));

        // O mesmo código, no mesmo passo, deixou de valer (proteção de repetição).
        verify(HOST_A, challenge, code).andExpect(status().isUnauthorized());
    }

    @Test
    void codigosDeRecuperacaoServemUmaVezCada() throws Exception {
        Inscricao inscricao = inscrever();
        String primeiro = inscricao.recoveryCodes().get(0);
        String segundo = inscricao.recoveryCodes().get(1);

        tokensDe(verify(HOST_A, desafioDeVerificacao(HOST_A, ADMIN), primeiro));
        verify(HOST_A, desafioDeVerificacao(HOST_A, ADMIN), primeiro).andExpect(status().isUnauthorized());
        tokensDe(verify(HOST_A, desafioDeVerificacao(HOST_A, ADMIN), segundo));

        mvc.perform(get("/api/v1/auth/mfa").header("Authorization", "Bearer " + tokensDe(
                        verify(HOST_A, desafioDeVerificacao(HOST_A, ADMIN), inscricao.recoveryCodes().get(2))))
                        .with(host(HOST_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodesLeft").value(7));
    }

    @Test
    void codigoErradoNaoAbreSessao() throws Exception {
        inscrever();

        verify(HOST_A, desafioDeVerificacao(HOST_A, ADMIN), CODIGO_INVALIDO)
                .andExpect(status().isUnauthorized());
    }

    // --- ativação opcional e desativação ---

    @Test
    void treinadorEntraSemMfaEPodeAtivar() throws Exception {
        String token = acesso(HOST_A, TREINADOR);

        mvc.perform(get("/api/v1/auth/mfa").header("Authorization", "Bearer " + token).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        String setup = mvc.perform(post("/api/v1/auth/mfa/setup").header("Authorization", "Bearer " + token)
                        .with(host(HOST_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(setup, "$.secret");

        mvc.perform(post("/api/v1/auth/mfa/activate").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + codigo(secret) + "\"}").with(host(HOST_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10));

        // Passa a ser pedido o segundo fator também a este perfil.
        login(HOST_A, TREINADOR)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true));
    }

    @Test
    void desativarExigePasswordECodigoValido() throws Exception {
        Inscricao inscricao = inscrever();
        String token = tokensDe(verify(HOST_A, desafioDeVerificacao(HOST_A, ADMIN),
                inscricao.recoveryCodes().get(0)));

        desativar(HOST_A, token, "password-errada", inscricao.recoveryCodes().get(1))
                .andExpect(status().isForbidden());
        desativar(HOST_A, token, PASS, CODIGO_INVALIDO).andExpect(status().isForbidden());

        desativar(HOST_A, token, PASS, inscricao.recoveryCodes().get(1)).andExpect(status().isNoContent());

        // Sem MFA, o ADMIN volta a ser obrigado a inscrever-se.
        login(HOST_A, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnrollmentRequired").value(true));
    }

    // --- auditoria (só escrita: não há endpoint de leitura) ---

    @Test
    void passosDoMfaFicamNaAuditoria() throws Exception {
        inscrever();
        verify(HOST_A, desafioDeVerificacao(HOST_A, ADMIN), CODIGO_INVALIDO).andExpect(status().isUnauthorized());

        assertThat(contarAuditoria("MFA_ENROLLMENT_REQUIRED")).isPositive();
        assertThat(contarAuditoria("MFA_ENROLLMENT_STARTED")).isPositive();
        assertThat(contarAuditoria("MFA_ENROLLED")).isPositive();
        assertThat(contarAuditoria("MFA_VERIFICATION_FAILED")).isPositive();
    }

    // --- auxiliares ---

    private ResultActions login(String host, String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASS + "\"}")
                .with(host(host)));
    }

    private String desafioDeInscricao(String host, String email) throws Exception {
        String corpo = login(host, email).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnrollmentRequired").value(true))
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String challenge = JsonPath.read(corpo, "$.challengeToken");
        assertThat(challenge).isNotBlank();
        return challenge;
    }

    private String desafioDeVerificacao(String host, String email) throws Exception {
        String corpo = login(host, email).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaEnrollmentRequired").value(false))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.challengeToken");
    }

    private String setup(String host, String challenge) throws Exception {
        return mvc.perform(post("/api/v1/auth/mfa/enrollment/setup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"" + challenge + "\"}").with(host(host)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions confirmarInscricao(String host, String challenge, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/mfa/enrollment/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeToken\":\"" + challenge + "\",\"code\":\"" + code + "\"}")
                .with(host(host)));
    }

    private ResultActions verify(String host, String challenge, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeToken\":\"" + challenge + "\",\"code\":\"" + code + "\"}")
                .with(host(host)));
    }

    private ResultActions desativar(String host, String token, String password, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/mfa/disable").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\",\"code\":\"" + code + "\"}")
                .with(host(host)));
    }

    /** Inscreve o ADMIN pela via do desafio de login e devolve o segredo e os códigos de recuperação. */
    private Inscricao inscrever() throws Exception {
        String challenge = desafioDeInscricao(HOST_A, ADMIN);
        String secret = JsonPath.read(setup(HOST_A, challenge), "$.secret");
        String corpo = confirmarInscricao(HOST_A, challenge, codigo(secret))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Inscricao(secret, JsonPath.read(corpo, "$.recoveryCodes"));
    }

    private static String tokensDe(ResultActions verify) throws Exception {
        return JsonPath.read(verify.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.accessToken");
    }

    private static String codigo(String secret) {
        return Totp.code(secret, Instant.now().getEpochSecond() / Totp.PERIOD_SECONDS);
    }

    /** O passo da inscrição já está usado; a janela seguinte é aceite pela tolerância. */
    private static String codigoDaJanelaSeguinte(String secret) {
        return Totp.code(secret, Instant.now().getEpochSecond() / Totp.PERIOD_SECONDS + 1);
    }

    private static void desligar(Connection c, UUID userId) throws SQLException {
        executar(c, """
                UPDATE app_user SET mfa_secret = NULL, mfa_pending_at = NULL, mfa_enabled_at = NULL,
                    mfa_last_used_step = NULL WHERE id = ?
                """, userId);
        executar(c, "DELETE FROM mfa_recovery_code WHERE user_id = ?", userId);
    }
}
