package pt.voleibol.gestao.billing;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Financeiro contra PostgreSQL real: planos de quotas, cobranças, pagamentos e lançamentos.
 * Cobre CRUD, validação, autorização por papel (gestão e financeiro escrevem, treinador não entra,
 * atleta/encarregado só vêem as suas cobranças), isolamento entre clubes, remoção não destrutiva
 * e auditoria.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
class BillingFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000d401");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000d402");

    static final String HOST_A = "clube-billing.localhost";
    static final String HOST_B = "clube-billing-b.localhost";
    static final String PASS = "palavra-passe-teste";

    static final String ADMIN = "admin@billing.test";
    static final String DIRECAO = "direcao@billing.test";
    static final String FINANCEIRO = "financeiro@billing.test";
    static final String TREINADOR = "treinador@billing.test";
    static final String ATLETA_USER = "atleta@billing.test";
    static final String ENCARREGADO = "encarregado@billing.test";
    static final String ADMIN_B = "admin@billing-b.test";

    static final UUID ATLETA_1 = UUID.fromString("00000000-0000-0000-0000-00000000f401");
    static final UUID ATLETA_2 = UUID.fromString("00000000-0000-0000-0000-00000000f402");
    static final UUID ATLETA_B = UUID.fromString("00000000-0000-0000-0000-00000000f403");

    static final UUID PLANO_A = UUID.fromString("00000000-0000-0000-0000-00000000c401");
    static final UUID PLANO_B = UUID.fromString("00000000-0000-0000-0000-00000000c402");
    static final UUID COBRANCA_A = UUID.fromString("00000000-0000-0000-0000-00000000b401");
    static final UUID COBRANCA_OUTRA = UUID.fromString("00000000-0000-0000-0000-00000000b402");
    static final UUID COBRANCA_B = UUID.fromString("00000000-0000-0000-0000-00000000b403");
    static final UUID PAGAMENTO_A = UUID.fromString("00000000-0000-0000-0000-00000000a401");
    static final UUID LANCAMENTO_A = UUID.fromString("00000000-0000-0000-0000-00000000e401");
    static final UUID LANCAMENTO_B = UUID.fromString("00000000-0000-0000-0000-00000000e402");

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

    // --- fluxo principal ---

    @Test
    void fluxoCompletoFinanceiro() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String plano = criarPlano(HOST_A, admin,
                "{\"name\":\"Inscrição\",\"amountCents\":2500,\"periodicity\":\"ONE_TIME\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID planoId = UUID.fromString(JsonPath.read(plano, "$.id"));
        assertThat((Integer) JsonPath.read(plano, "$.amountCents")).isEqualTo(2500);

        // O nome é único por clube sem distinguir maiúsculas.
        criarPlano(HOST_A, admin, "{\"name\":\"inscrição\",\"amountCents\":2500}")
                .andExpect(status().isConflict());
        String planos = mvc.perform(get("/api/v1/fee-plans")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(planos)).contains(PLANO_A, planoId);

        String cobranca = criarCobranca(HOST_A, admin, ATLETA_1, null, PLANO_A, 7500,
                "\"2026-03-01\"", null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID cobrancaId = UUID.fromString(JsonPath.read(cobranca, "$.id"));
        assertThat((String) JsonPath.read(cobranca, "$.status")).isEqualTo("PENDING");
        assertThat((Integer) JsonPath.read(cobranca, "$.amountCents")).isEqualTo(7500);

        mvc.perform(patch("/api/v1/charges/" + cobrancaId + "/status")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isNoContent());
        String pagas = mvc.perform(get("/api/v1/charges").param("status", "PAID")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(pagas)).contains(cobrancaId).doesNotContain(COBRANCA_A);

        String pagamento = mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargeId\":\"" + cobrancaId
                                + "\",\"amountCents\":7500,\"method\":\"MBWAY\",\"reference\":\"REF-1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(pagamento, "$.reference")).isEqualTo("REF-1");
        String pagamentos = mvc.perform(get("/api/v1/charges/" + cobrancaId + "/payments")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(pagamentos, "$[*].reference")).containsExactly("REF-1");

        String lancamento = mvc.perform(post("/api/v1/ledger-entries")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INCOME\",\"category\":\"Quotas\",\"amountCents\":7500,"
                                + "\"entryDate\":\"2026-03-01\",\"description\":\"Quota de março\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID lancamentoId = UUID.fromString(JsonPath.read(lancamento, "$.id"));
        String lancamentos = mvc.perform(get("/api/v1/ledger-entries")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lancamentos)).contains(LANCAMENTO_A, lancamentoId);

        mvc.perform(delete("/api/v1/charges/" + cobrancaId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/charges/" + cobrancaId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        String depois = mvc.perform(get("/api/v1/charges")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(depois)).contains(COBRANCA_A, COBRANCA_OUTRA).doesNotContain(cobrancaId);
        assertThat(contar("SELECT count(*) FROM payment WHERE charge_id = ?", cobrancaId)).isEqualTo(1);

        mvc.perform(delete("/api/v1/fee-plans/" + planoId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/fee-plans/" + planoId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/ledger-entries/" + lancamentoId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/ledger-entries/" + lancamentoId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        assertThat(contarAuditoria("FEE_PLAN_CREATED")).isPositive();
        assertThat(contarAuditoria("FEE_PLAN_ANONYMIZED")).isPositive();
        assertThat(contarAuditoria("CHARGE_CREATED")).isPositive();
        assertThat(contarAuditoria("CHARGE_STATUS_UPDATED")).isPositive();
        assertThat(contarAuditoria("CHARGE_ANONYMIZED")).isPositive();
        assertThat(contarAuditoria("PAYMENT_CREATED")).isPositive();
        assertThat(contarAuditoria("LEDGER_ENTRY_CREATED")).isPositive();
        assertThat(contarAuditoria("LEDGER_ENTRY_ANONYMIZED")).isPositive();
    }

    // --- validação ---

    @Test
    void validacoesFinanceiro() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        criarPlano(HOST_A, admin, "{\"amountCents\":1000}").andExpect(status().isBadRequest());
        criarPlano(HOST_A, admin, "{\"name\":\" \",\"amountCents\":1000}")
                .andExpect(status().isBadRequest());
        criarPlano(HOST_A, admin, "{\"name\":\"Negativo\",\"amountCents\":-1}")
                .andExpect(status().isBadRequest());
        criarPlano(HOST_A, admin,
                "{\"name\":\"Inválido\",\"amountCents\":1000,\"periodicity\":\"SEMANAL\"}")
                .andExpect(status().isBadRequest());

        criarCobranca(HOST_A, admin, null, null, null, 1000, "\"2026-03-01\"", null)
                .andExpect(status().isBadRequest());
        criarCobranca(HOST_A, admin, ATLETA_1, null, null, 1000, null, null)
                .andExpect(status().isBadRequest());
        criarCobranca(HOST_A, admin, ATLETA_1, null, null, 1000, "\"2026-03-01\"", "BANIDO")
                .andExpect(status().isBadRequest());
        criarCobranca(HOST_A, admin, ATLETA_1, null, null, -1, "\"2026-03-01\"", null)
                .andExpect(status().isBadRequest());
        criarCobranca(HOST_A, admin, UUID.randomUUID(), null, null, 1000, "\"2026-03-01\"", null)
                .andExpect(status().isNotFound());
        criarCobranca(HOST_A, admin, ATLETA_1, UUID.randomUUID(), null, 1000, "\"2026-03-01\"", null)
                .andExpect(status().isNotFound());
        criarCobranca(HOST_A, admin, ATLETA_1, null, UUID.randomUUID(), 1000, "\"2026-03-01\"", null)
                .andExpect(status().isNotFound());
        criarCobranca(HOST_A, admin, ATLETA_B, null, null, 1000, "\"2026-03-01\"", null)
                .andExpect(status().isNotFound());

        mvc.perform(patch("/api/v1/charges/" + COBRANCA_A + "/status")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TALVEZ\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/charges/" + COBRANCA_A + "/status")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/charges/" + UUID.randomUUID() + "/status")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":1000}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargeId\":\"" + UUID.randomUUID() + "\",\"amountCents\":1000}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargeId\":\"" + COBRANCA_A
                                + "\",\"amountCents\":1000,\"method\":\"BITCOIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargeId\":\"" + COBRANCA_A + "\",\"amountCents\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/charges/" + UUID.randomUUID() + "/payments")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/ledger-entries")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DONATION\",\"category\":\"Quotas\",\"amountCents\":1000,"
                                + "\"entryDate\":\"2026-03-01\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ledger-entries")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INCOME\",\"amountCents\":1000,\"entryDate\":\"2026-03-01\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ledger-entries")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INCOME\",\"category\":\"Quotas\",\"amountCents\":-1,"
                                + "\"entryDate\":\"2026-03-01\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/v1/fee-plans/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/charges/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/ledger-entries/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
    }

    // --- autorização por papel ---

    @Test
    void autorizacaoPorPapelFinanceiro() throws Exception {
        String direcao = acesso(HOST_A, DIRECAO);
        criarPlano(HOST_A, direcao, "{\"name\":\"Plano da Direção\",\"amountCents\":1000}")
                .andExpect(status().isCreated());
        criarCobranca(HOST_A, direcao, ATLETA_2, null, null, 1000, "\"2026-04-01\"", null)
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + direcao).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargeId\":\"" + COBRANCA_A + "\",\"amountCents\":1000}"))
                .andExpect(status().isCreated());

        // FINANCEIRO gere cobranças, pagamentos e lançamentos.
        String financeiro = acesso(HOST_A, FINANCEIRO);
        criarPlano(HOST_A, financeiro, "{\"name\":\"Plano Financeiro\",\"amountCents\":1000}")
                .andExpect(status().isCreated());
        criarCobranca(HOST_A, financeiro, ATLETA_2, null, null, 1000, "\"2026-04-01\"", null)
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + financeiro).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargeId\":\"" + COBRANCA_OUTRA + "\",\"amountCents\":1000}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/ledger-entries")
                        .header("Authorization", "Bearer " + financeiro).with(host(HOST_A)))
                .andExpect(status().isOk());

        // TREINADOR não entra no financeiro.
        String treinador = acesso(HOST_A, TREINADOR);
        mvc.perform(get("/api/v1/fee-plans").header("Authorization", "Bearer " + treinador)
                        .with(host(HOST_A)))
                .andExpect(status().isForbidden());
        criarPlano(HOST_A, treinador, "{\"name\":\"Plano do Treinador\",\"amountCents\":1000}")
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/charges").header("Authorization", "Bearer " + treinador)
                        .with(host(HOST_A)))
                .andExpect(status().isForbidden());
        criarCobranca(HOST_A, treinador, ATLETA_1, null, null, 1000, "\"2026-04-01\"", null)
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ledger-entries").header("Authorization", "Bearer " + treinador)
                        .with(host(HOST_A)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/charges/" + COBRANCA_A + "/payments")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isForbidden());

        // ATLETA só vê as suas cobranças e não escreve.
        String atleta = acesso(HOST_A, ATLETA_USER);
        String cobrancasAtleta = mvc.perform(get("/api/v1/charges")
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(cobrancasAtleta)).containsExactly(COBRANCA_A);
        String outras = mvc.perform(get("/api/v1/charges").param("athleteId", ATLETA_2.toString())
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(outras)).isEmpty();
        criarPlano(HOST_A, atleta, "{\"name\":\"Plano do Atleta\",\"amountCents\":1000}")
                .andExpect(status().isForbidden());
        criarCobranca(HOST_A, atleta, ATLETA_1, null, null, 1000, "\"2026-04-01\"", null)
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/charges/" + COBRANCA_A + "/payments")
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ledger-entries").header("Authorization", "Bearer " + atleta)
                        .with(host(HOST_A)))
                .andExpect(status().isForbidden());

        // ENCARREGADO vê as cobranças do educando, mas não escreve.
        String encarregado = acesso(HOST_A, ENCARREGADO);
        String cobrancasEncarregado = mvc.perform(get("/api/v1/charges")
                        .header("Authorization", "Bearer " + encarregado).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(cobrancasEncarregado)).containsExactly(COBRANCA_A);
        criarCobranca(HOST_A, encarregado, ATLETA_1, null, null, 1000, "\"2026-04-01\"", null)
                .andExpect(status().isForbidden());
    }

    // --- isolamento entre clubes ---

    @Test
    void isolamentoEntreClubes() throws Exception {
        String adminA = acesso(HOST_A, ADMIN);
        String adminB = acesso(HOST_B, ADMIN_B);

        String cobrancasA = mvc.perform(get("/api/v1/charges")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(cobrancasA)).contains(COBRANCA_A, COBRANCA_OUTRA).doesNotContain(COBRANCA_B);
        String planosA = mvc.perform(get("/api/v1/fee-plans")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(planosA)).contains(PLANO_A).doesNotContain(PLANO_B);
        String lancamentosA = mvc.perform(get("/api/v1/ledger-entries")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lancamentosA)).contains(LANCAMENTO_A).doesNotContain(LANCAMENTO_B);

        mvc.perform(patch("/api/v1/charges/" + COBRANCA_B + "/status")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/charges/" + COBRANCA_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargeId\":\"" + COBRANCA_B + "\",\"amountCents\":1000}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/charges/" + COBRANCA_B + "/payments")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        criarCobranca(HOST_A, adminA, ATLETA_B, null, null, 1000, "\"2026-04-01\"", null)
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/fee-plans/" + PLANO_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/ledger-entries/" + LANCAMENTO_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        String cobrancasB = mvc.perform(get("/api/v1/charges")
                        .header("Authorization", "Bearer " + adminB).with(host(HOST_B)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(cobrancasB)).containsExactly(COBRANCA_B);
        String planosB = mvc.perform(get("/api/v1/fee-plans")
                        .header("Authorization", "Bearer " + adminB).with(host(HOST_B)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(planosB)).containsExactly(PLANO_B);
        String lancamentosB = mvc.perform(get("/api/v1/ledger-entries")
                        .header("Authorization", "Bearer " + adminB).with(host(HOST_B)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lancamentosB)).containsExactly(LANCAMENTO_B);
    }

    @Test
    void pedidoSemAutenticacao() throws Exception {
        mvc.perform(get("/api/v1/fee-plans").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/charges").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ledger-entries").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
    }

    // --- auxiliares de pedido ---

    private ResultActions criarPlano(String host, String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/fee-plans")
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions criarCobranca(String host, String token, UUID athleteId, UUID guardianId,
                                        UUID feePlanId, long amountCents, String dueDate,
                                        String status) throws Exception {
        String body = "{\"athleteId\":" + json(athleteId) + ",\"guardianId\":" + json(guardianId)
                + ",\"feePlanId\":" + json(feePlanId) + ",\"amountCents\":" + amountCents
                + ",\"dueDate\":" + dueDate + ",\"status\":" + json(status) + "}";
        return mvc.perform(post("/api/v1/charges")
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON).content(body));
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
            clube(c, CLUBE_A, "Clube Billing", "clube-billing");
            clube(c, CLUBE_B, "Clube Billing B", "clube-billing-b");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, ATLETA_USER, CLUBE_A, "ATLETA");
            membro(c, ENCARREGADO, CLUBE_A, "ENCARREGADO");
            membro(c, ADMIN_B, CLUBE_B, "ADMIN");

            atleta(c, ATLETA_1, CLUBE_A, "Atleta Um", "F-A1", id(ATLETA_USER));
            atleta(c, ATLETA_2, CLUBE_A, "Atleta Dois", "F-A2", null);
            atleta(c, ATLETA_B, CLUBE_B, "Atleta de Outro Clube", "F-B1", null);

            executar(c, """
                    INSERT INTO athlete_guardian (tenant_id, athlete_id, user_id, relationship)
                    VALUES (?, ?, ?, 'MAE') ON CONFLICT (tenant_id, athlete_id, user_id) DO NOTHING
                    """, CLUBE_A, ATLETA_1, id(ENCARREGADO));

            plano(c, PLANO_A, CLUBE_A, "Mensalidade", 5000);
            plano(c, PLANO_B, CLUBE_B, "Mensalidade B", 4500);

            cobranca(c, COBRANCA_A, CLUBE_A, ATLETA_1, id(ENCARREGADO), PLANO_A, 5000, "2026-03-01");
            cobranca(c, COBRANCA_OUTRA, CLUBE_A, ATLETA_2, null, PLANO_A, 5000, "2026-03-01");
            cobranca(c, COBRANCA_B, CLUBE_B, ATLETA_B, null, PLANO_B, 4500, "2026-03-01");

            executar(c, """
                    INSERT INTO payment (id, tenant_id, charge_id, method, amount_cents, reference)
                    VALUES (?, ?, ?, 'CASH', 1000, 'SEED-1')
                    ON CONFLICT (id) DO UPDATE SET charge_id = EXCLUDED.charge_id,
                        method = EXCLUDED.method, amount_cents = EXCLUDED.amount_cents,
                        reference = EXCLUDED.reference, anonymized_at = NULL
                    """, PAGAMENTO_A, CLUBE_A, COBRANCA_A);

            lancamento(c, LANCAMENTO_A, CLUBE_A, "INCOME", "Quotas", 5000, "2026-03-01");
            lancamento(c, LANCAMENTO_B, CLUBE_B, "EXPENSE", "Material", 2000, "2026-03-02");
        }
    }

    private static void limpar(Connection c) throws SQLException {
        executar(c, "DELETE FROM payment WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
        executar(c, "DELETE FROM charge WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
        executar(c, "DELETE FROM ledger_entry WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
        executar(c, "DELETE FROM fee_plan WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
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

    private static void atleta(Connection c, UUID athleteId, UUID tenantId, String nome, String licenca,
                               UUID userId) throws SQLException {
        executar(c, """
                INSERT INTO athlete (id, tenant_id, full_name, birth_date, gender, license_number, status, user_id)
                VALUES (?, ?, ?, ?, 'F', ?, 'ACTIVE', ?)
                ON CONFLICT (id) DO UPDATE SET full_name = EXCLUDED.full_name,
                    license_number = EXCLUDED.license_number, status = 'ACTIVE',
                    user_id = EXCLUDED.user_id, anonymized_at = NULL
                """, athleteId, tenantId, nome, Date.valueOf("2010-01-01"), licenca, userId);
    }

    private static void plano(Connection c, UUID planId, UUID tenantId, String nome, long amountCents)
            throws SQLException {
        executar(c, """
                INSERT INTO fee_plan (id, tenant_id, name, amount_cents, periodicity)
                VALUES (?, ?, ?, ?, 'MONTHLY')
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,
                    amount_cents = EXCLUDED.amount_cents, periodicity = 'MONTHLY', anonymized_at = NULL
                """, planId, tenantId, nome, amountCents);
    }

    private static void cobranca(Connection c, UUID chargeId, UUID tenantId, UUID athleteId,
                                 UUID guardianId, UUID planId, long amountCents, String dueDate)
            throws SQLException {
        executar(c, """
                INSERT INTO charge (id, tenant_id, athlete_id, guardian_id, fee_plan_id, amount_cents,
                                    due_date, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (id) DO UPDATE SET athlete_id = EXCLUDED.athlete_id,
                    guardian_id = EXCLUDED.guardian_id, fee_plan_id = EXCLUDED.fee_plan_id,
                    amount_cents = EXCLUDED.amount_cents, due_date = EXCLUDED.due_date,
                    status = 'PENDING', anonymized_at = NULL
                """, chargeId, tenantId, athleteId, guardianId, planId, amountCents,
                Date.valueOf(dueDate));
    }

    private static void lancamento(Connection c, UUID ledgerId, UUID tenantId, String type,
                                   String category, long amountCents, String entryDate)
            throws SQLException {
        executar(c, """
                INSERT INTO ledger_entry (id, tenant_id, type, category, amount_cents, entry_date, description)
                VALUES (?, ?, ?, ?, ?, ?, 'Lançamento semeado')
                ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, category = EXCLUDED.category,
                    amount_cents = EXCLUDED.amount_cents, entry_date = EXCLUDED.entry_date,
                    anonymized_at = NULL
                """, ledgerId, tenantId, type, category, amountCents, Date.valueOf(entryDate));
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
