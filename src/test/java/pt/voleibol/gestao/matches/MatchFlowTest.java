package pt.voleibol.gestao.matches;

import com.jayway.jsonpath.JsonPath;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Jogos contra PostgreSQL real: CRUD, resultados por set, convocatórias e respostas, validação,
 * autorização por papel (gestão, treinador, atleta no plantel e encarregado só leitura),
 * isolamento entre clubes e remoção não destrutiva.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
class MatchFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000d301");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000d302");

    static final String HOST_A = "clube-jogos.localhost";
    static final String HOST_B = "clube-jogos-b.localhost";
    static final String PASS = "palavra-passe-teste";

    static final String ADMIN = "admin@jogos.test";
    static final String DIRECAO = "direcao@jogos.test";
    static final String TREINADOR = "treinador@jogos.test";
    static final String FINANCEIRO = "financeiro@jogos.test";
    static final String ATLETA_USER = "atleta@jogos.test";
    static final String ENCARREGADO = "encarregado@jogos.test";
    static final String ADMIN_B = "admin@jogos-b.test";

    static final UUID EQUIPA_A = UUID.fromString("00000000-0000-0000-0000-00000000e301");
    static final UUID EQUIPA_OUTRA = UUID.fromString("00000000-0000-0000-0000-00000000e302");
    static final UUID EQUIPA_B = UUID.fromString("00000000-0000-0000-0000-00000000e303");

    static final UUID ATLETA_1 = UUID.fromString("00000000-0000-0000-0000-00000000f301");
    static final UUID ATLETA_2 = UUID.fromString("00000000-0000-0000-0000-00000000f302");
    static final UUID ATLETA_B = UUID.fromString("00000000-0000-0000-0000-00000000f303");

    static final UUID VENUE_A = UUID.fromString("00000000-0000-0000-0000-00000000bb01");
    static final UUID VENUE_B = UUID.fromString("00000000-0000-0000-0000-00000000bb02");

    static final UUID JOGO_A = UUID.fromString("00000000-0000-0000-0000-00000000bc01");
    static final UUID JOGO_OUTRA = UUID.fromString("00000000-0000-0000-0000-00000000bc02");
    static final UUID JOGO_B = UUID.fromString("00000000-0000-0000-0000-00000000bc03");

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
    void jogosFluxoCompleto() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criado = criarJogo(HOST_A, admin, EQUIPA_A, "Adversário Novo", VENUE_A,
                "2027-02-01T15:00:00Z", false, "SCHEDULED", "Taça")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criado, "$.id"));
        assertThat((Boolean) JsonPath.read(criado, "$.isHome")).isFalse();
        assertThat((String) JsonPath.read(criado, "$.status")).isEqualTo("SCHEDULED");

        String detalhe = mvc.perform(get("/api/v1/matches/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(detalhe, "$.opponent")).isEqualTo("Adversário Novo");

        mvc.perform(put("/api/v1/matches/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jogo(EQUIPA_A, "Adversário Renomeado", VENUE_A,
                                "2027-02-01T16:00:00Z", true, "PLAYED", "Taça", "Pavilhão Principal")))
                .andExpect(status().isOk());

        String porEquipa = mvc.perform(get("/api/v1/matches").param("teamId", EQUIPA_A.toString())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(porEquipa)).contains(id, JOGO_A).doesNotContain(JOGO_OUTRA);

        // Resultados por set: upsert por POST/PUT, listagem e remoção.
        mvc.perform(post("/api/v1/matches/" + id + "/set-scores")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setNumber\":1,\"ourPoints\":25,\"opponentPoints\":20}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/matches/" + id + "/set-scores")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setNumber\":2,\"ourPoints\":25,\"opponentPoints\":22}"))
                .andExpect(status().isNoContent());
        String sets = mvc.perform(get("/api/v1/matches/" + id + "/set-scores")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(sets, "$[*].setNumber")).containsExactly(1, 2);

        mvc.perform(put("/api/v1/matches/" + id + "/set-scores/2")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setNumber\":2,\"ourPoints\":20,\"opponentPoints\":25}"))
                .andExpect(status().isNoContent());
        String setsAtualizados = mvc.perform(get("/api/v1/matches/" + id + "/set-scores")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(setsAtualizados, "$[1].ourPoints")).isEqualTo(20);

        mvc.perform(delete("/api/v1/matches/" + id + "/set-scores/2")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/matches/" + id + "/set-scores/2")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        // Convocatórias e respostas.
        mvc.perform(post("/api/v1/matches/" + id + "/call-ups")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"athleteId\":\"" + ATLETA_1 + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/matches/" + id + "/call-ups")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"athleteId\":\"" + ATLETA_1 + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/matches/" + id + "/call-ups")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"athleteId\":\"" + ATLETA_2 + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(patch("/api/v1/matches/" + id + "/call-ups/" + ATLETA_1)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"CONFIRMED\"}"))
                .andExpect(status().isNoContent());
        String convocatorias = mvc.perform(get("/api/v1/matches/" + id + "/call-ups")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(convocatorias, "$[*].athleteId"))
                .containsExactlyInAnyOrder(ATLETA_1.toString(), ATLETA_2.toString());
        assertThat(JsonPath.<List<String>>read(convocatorias, "$[*].response"))
                .contains("CONFIRMED");

        mvc.perform(post("/api/v1/matches/" + id + "/call-ups/" + ATLETA_2 + "/respond")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"DECLINED\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/matches/" + id + "/call-ups/" + ATLETA_2)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/matches/" + id + "/call-ups/" + ATLETA_2)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/matches/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/matches/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        assertThat(contarAuditoria("MATCH_CREATED")).isPositive();
        assertThat(contarAuditoria("MATCH_UPDATED")).isPositive();
        assertThat(contarAuditoria("MATCH_SET_SCORE_SET")).isPositive();
        assertThat(contarAuditoria("MATCH_CALLUP_ADDED")).isPositive();
        assertThat(contarAuditoria("MATCH_CALLUP_RESPONDED")).isPositive();
        assertThat(contarAuditoria("MATCH_ANONYMIZED")).isPositive();
    }

    @Test
    void remocaoDeJogoPreservaSetsEConvocatorias() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        mvc.perform(delete("/api/v1/matches/" + JOGO_A)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());

        try (Connection c = superuser(); PreparedStatement ps = c.prepareStatement(
                "SELECT status, anonymized_at FROM match WHERE id = ?")) {
            ps.setObject(1, JOGO_A);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("CANCELLED");
                assertThat(rs.getObject("anonymized_at")).isNotNull();
            }
        }
        assertThat(contar("SELECT count(*) FROM set_score WHERE match_id = ?", JOGO_A)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM call_up WHERE match_id = ?", JOGO_A)).isEqualTo(1);

        mvc.perform(get("/api/v1/matches/" + JOGO_A + "/set-scores")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/matches/" + JOGO_A + "/call-ups")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        String lista = mvc.perform(get("/api/v1/matches")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lista)).doesNotContain(JOGO_A);
    }

    // --- validação ---

    @Test
    void validacoesDeJogos() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        mvc.perform(post("/api/v1/matches").header("Authorization", "Bearer " + admin)
                        .with(host(HOST_A)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opponent\":\"Sem equipa\",\"matchDate\":\"2027-02-01T15:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/matches").header("Authorization", "Bearer " + admin)
                        .with(host(HOST_A)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"" + EQUIPA_A + "\",\"matchDate\":\"2027-02-01T15:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/matches").header("Authorization", "Bearer " + admin)
                        .with(host(HOST_A)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"" + EQUIPA_A + "\",\"opponent\":\"Adversário\"}"))
                .andExpect(status().isBadRequest());
        criarJogo(HOST_A, admin, EQUIPA_A, "Adversário", null,
                "2027-02-01T15:00:00Z", null, "BANIDO", null)
                .andExpect(status().isBadRequest());
        criarJogo(HOST_A, admin, UUID.randomUUID(), "Adversário", null,
                "2027-02-01T15:00:00Z", null, null, null)
                .andExpect(status().isNotFound());
        criarJogo(HOST_A, admin, EQUIPA_A, "Adversário", UUID.randomUUID(),
                "2027-02-01T15:00:00Z", null, null, null)
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/matches/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/matches/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jogo(EQUIPA_A, "Adversário", null, "2027-02-01T15:00:00Z", null, null, null, null)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/matches/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/matches/" + UUID.randomUUID() + "/set-scores")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setNumber\":1,\"ourPoints\":25,\"opponentPoints\":20}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/matches/" + JOGO_A + "/set-scores")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setNumber\":0,\"ourPoints\":25,\"opponentPoints\":20}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/matches/" + JOGO_A + "/set-scores/9")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/matches/" + JOGO_A + "/call-ups")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"athleteId\":\"" + ATLETA_B + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/matches/" + JOGO_A + "/call-ups/" + ATLETA_2)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/matches/" + JOGO_A + "/call-ups/" + ATLETA_2)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"CONFIRMED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/matches/" + JOGO_A + "/call-ups/" + ATLETA_1)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"TALVEZ\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/matches/" + UUID.randomUUID() + "/call-ups/" + ATLETA_1)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"CONFIRMED\"}"))
                .andExpect(status().isNotFound());
    }

    // --- autorização por papel ---

    @Test
    void autorizacaoPorPapelEmJogos() throws Exception {
        String direcao = acesso(HOST_A, DIRECAO);
        criarJogo(HOST_A, direcao, EQUIPA_A, "Adversário da Direção", VENUE_A,
                "2027-02-03T15:00:00Z", true, null, null)
                .andExpect(status().isCreated());

        // FINANCEIRO não tem acesso à gestão desportiva.
        String financeiro = acesso(HOST_A, FINANCEIRO);
        mvc.perform(get("/api/v1/matches").header("Authorization", "Bearer " + financeiro)
                        .with(host(HOST_A)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/matches/" + JOGO_A).header("Authorization", "Bearer " + financeiro)
                        .with(host(HOST_A)))
                .andExpect(status().isForbidden());

        // TREINADOR só nos jogos das equipas em que é staff.
        String treinador = acesso(HOST_A, TREINADOR);
        String listaTreinador = mvc.perform(get("/api/v1/matches")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(listaTreinador)).contains(JOGO_A).doesNotContain(JOGO_OUTRA);
        mvc.perform(get("/api/v1/matches/" + JOGO_OUTRA)
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        criarJogo(HOST_A, treinador, EQUIPA_A, "Adversário do Treinador", VENUE_A,
                "2027-02-04T15:00:00Z", true, null, null)
                .andExpect(status().isCreated());
        criarJogo(HOST_A, treinador, EQUIPA_OUTRA, "Adversário", VENUE_A,
                "2027-02-04T15:00:00Z", true, null, null)
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/matches/" + JOGO_A + "/set-scores")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setNumber\":2,\"ourPoints\":25,\"opponentPoints\":10}"))
                .andExpect(status().isNoContent());

        // ATLETA vê os jogos da sua equipa e responde à própria convocatória, mas não gere.
        String atleta = acesso(HOST_A, ATLETA_USER);
        String listaAtleta = mvc.perform(get("/api/v1/matches")
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(listaAtleta)).contains(JOGO_A).doesNotContain(JOGO_OUTRA);
        mvc.perform(get("/api/v1/matches/" + JOGO_OUTRA)
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        criarJogo(HOST_A, atleta, EQUIPA_A, "Adversário proibido", VENUE_A,
                "2027-02-05T15:00:00Z", true, null, null)
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/matches/" + JOGO_A + "/set-scores")
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setNumber\":3,\"ourPoints\":25,\"opponentPoints\":10}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/matches/" + JOGO_A + "/call-ups")
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"athleteId\":\"" + ATLETA_2 + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/matches/" + JOGO_A + "/call-ups/" + ATLETA_1)
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"CONFIRMED\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(patch("/api/v1/matches/" + JOGO_A + "/call-ups/" + ATLETA_2)
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"CONFIRMED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/matches/" + JOGO_A + "/call-ups/" + ATLETA_1)
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isForbidden());

        // O ENCARREGADO vê os jogos do educando, mas não responde nem gere.
        String encarregado = acesso(HOST_A, ENCARREGADO);
        String listaEncarregado = mvc.perform(get("/api/v1/matches")
                        .header("Authorization", "Bearer " + encarregado).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(listaEncarregado)).contains(JOGO_A).doesNotContain(JOGO_OUTRA);
        mvc.perform(get("/api/v1/matches/" + JOGO_A)
                        .header("Authorization", "Bearer " + encarregado).with(host(HOST_A)))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/matches/" + JOGO_A + "/call-ups/" + ATLETA_1)
                        .header("Authorization", "Bearer " + encarregado).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"CONFIRMED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/matches/" + JOGO_A + "/set-scores")
                        .header("Authorization", "Bearer " + encarregado).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setNumber\":4,\"ourPoints\":25,\"opponentPoints\":10}"))
                .andExpect(status().isForbidden());
    }

    // --- isolamento entre clubes ---

    @Test
    void isolamentoEntreClubes() throws Exception {
        String adminA = acesso(HOST_A, ADMIN);
        String adminB = acesso(HOST_B, ADMIN_B);

        mvc.perform(get("/api/v1/matches/" + JOGO_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/matches/" + JOGO_B + "/set-scores")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setNumber\":1,\"ourPoints\":25,\"opponentPoints\":20}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/matches/" + JOGO_B + "/call-ups")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/matches/" + JOGO_B + "/call-ups/" + ATLETA_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"CONFIRMED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/matches/" + JOGO_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        String listaA = mvc.perform(get("/api/v1/matches")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(listaA)).contains(JOGO_A, JOGO_OUTRA).doesNotContain(JOGO_B);

        mvc.perform(get("/api/v1/matches/" + JOGO_A)
                        .header("Authorization", "Bearer " + adminB).with(host(HOST_B)))
                .andExpect(status().isNotFound());
        String listaB = mvc.perform(get("/api/v1/matches")
                        .header("Authorization", "Bearer " + adminB).with(host(HOST_B)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(listaB)).containsExactly(JOGO_B);
    }

    @Test
    void pedidoSemAutenticacao() throws Exception {
        mvc.perform(get("/api/v1/matches").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
    }

    // --- auxiliares de pedido ---

    private ResultActions criarJogo(String host, String token, UUID teamId, String opponent, UUID courtId,
                                    String date, Boolean isHome, String status, String competition)
            throws Exception {
        return mvc.perform(post("/api/v1/matches").header("Authorization", "Bearer " + token)
                .with(host(host)).contentType(MediaType.APPLICATION_JSON)
                .content(jogo(teamId, opponent, courtId, date, isHome, status, competition, null)));
    }

    private static String jogo(UUID teamId, String opponent, UUID courtId, String date, Boolean isHome,
                               String status, String competition, String location) {
        return "{\"teamId\":" + json(teamId) + ",\"opponent\":" + json(opponent)
                + ",\"courtId\":" + json(courtId) + ",\"matchDate\":\"" + date + "\""
                + ",\"isHome\":" + (isHome == null ? "null" : isHome)
                + ",\"status\":" + json(status) + ",\"competition\":" + json(competition)
                + ",\"location\":" + json(location) + "}";
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
            clube(c, CLUBE_A, "Clube Jogos", "clube-jogos");
            clube(c, CLUBE_B, "Clube Jogos B", "clube-jogos-b");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");
            membro(c, ATLETA_USER, CLUBE_A, "ATLETA");
            membro(c, ENCARREGADO, CLUBE_A, "ENCARREGADO");
            membro(c, ADMIN_B, CLUBE_B, "ADMIN");

            atleta(c, ATLETA_1, CLUBE_A, "Atleta Um", "2010-05-01", "F", "J-A1", id(ATLETA_USER));
            atleta(c, ATLETA_2, CLUBE_A, "Atleta Dois", "2010-05-02", "F", "J-A2", null);
            atleta(c, ATLETA_B, CLUBE_B, "Atleta de Outro Clube", "2010-05-03", "F", "J-B1", null);

            equipa(c, EQUIPA_A, CLUBE_A, "Equipa A");
            equipa(c, EQUIPA_OUTRA, CLUBE_A, "Equipa B");
            equipa(c, EQUIPA_B, CLUBE_B, "Equipa B");

            executar(c, """
                    INSERT INTO team_staff (tenant_id, team_id, user_id, role)
                    VALUES (?, ?, ?, 'TREINADOR') ON CONFLICT (tenant_id, team_id, user_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, id(TREINADOR));
            executar(c, """
                    INSERT INTO team_athlete (tenant_id, team_id, athlete_id)
                    VALUES (?, ?, ?) ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, ATLETA_1);
            executar(c, """
                    INSERT INTO team_athlete (tenant_id, team_id, athlete_id)
                    VALUES (?, ?, ?) ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, ATLETA_2);
            executar(c, """
                    INSERT INTO team_athlete (tenant_id, team_id, athlete_id)
                    VALUES (?, ?, ?) ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                    """, CLUBE_B, EQUIPA_B, ATLETA_B);
            executar(c, """
                    INSERT INTO athlete_guardian (tenant_id, athlete_id, user_id, relationship)
                    VALUES (?, ?, ?, 'MAE') ON CONFLICT (tenant_id, athlete_id, user_id) DO NOTHING
                    """, CLUBE_A, ATLETA_1, id(ENCARREGADO));

            local(c, VENUE_A, CLUBE_A, "Pavilhão Principal");
            local(c, VENUE_B, CLUBE_B, "Pavilhão de Outro Clube");

            jogo(c, JOGO_A, CLUBE_A, EQUIPA_A, VENUE_A, "Adversário A", "2027-02-01T15:00:00Z");
            jogo(c, JOGO_OUTRA, CLUBE_A, EQUIPA_OUTRA, null, "Adversário B", "2027-02-02T15:00:00Z");
            jogo(c, JOGO_B, CLUBE_B, EQUIPA_B, null, "Adversário de Outro Clube", "2027-02-03T15:00:00Z");

            executar(c, """
                    INSERT INTO set_score (tenant_id, match_id, set_number, our_points, opponent_points)
                    VALUES (?, ?, 1, 25, 20)
                    ON CONFLICT (tenant_id, match_id, set_number)
                    DO UPDATE SET our_points = EXCLUDED.our_points, opponent_points = EXCLUDED.opponent_points
                    """, CLUBE_A, JOGO_A);
            executar(c, """
                    INSERT INTO call_up (tenant_id, match_id, athlete_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT (tenant_id, match_id, athlete_id) DO UPDATE SET status = 'CALLED_UP'
                    """, CLUBE_A, JOGO_A, ATLETA_1);
            executar(c, "DELETE FROM call_up WHERE tenant_id = ? AND match_id = ? AND athlete_id = ?",
                    CLUBE_A, JOGO_A, ATLETA_2);
        }
    }

    private static void limpar(Connection c) throws SQLException {
        executar(c, "DELETE FROM call_up WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
        executar(c, "DELETE FROM set_score WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
        executar(c, "DELETE FROM match WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
        executar(c, "DELETE FROM venue WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
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

    private static void atleta(Connection c, UUID athleteId, UUID tenantId, String nome, String nascimento,
                               String genero, String licenca, UUID userId) throws SQLException {
        executar(c, """
                INSERT INTO athlete (id, tenant_id, full_name, birth_date, gender, license_number, status, user_id)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (id) DO UPDATE SET full_name = EXCLUDED.full_name,
                    birth_date = EXCLUDED.birth_date, gender = EXCLUDED.gender,
                    license_number = EXCLUDED.license_number, status = 'ACTIVE',
                    user_id = EXCLUDED.user_id, anonymized_at = NULL
                """, athleteId, tenantId, nome, Date.valueOf(nascimento), genero, licenca, userId);
    }

    private static void equipa(Connection c, UUID teamId, UUID tenantId, String nome) throws SQLException {
        executar(c, """
                INSERT INTO team (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE', anonymized_at = NULL
                """, teamId, tenantId, nome);
    }

    private static void local(Connection c, UUID venueId, UUID tenantId, String nome) throws SQLException {
        executar(c, """
                INSERT INTO venue (id, tenant_id, name) VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, anonymized_at = NULL
                """, venueId, tenantId, nome);
    }

    private static void jogo(Connection c, UUID matchId, UUID tenantId, UUID teamId, UUID courtId,
                             String opponent, String data) throws SQLException {
        executar(c, """
                INSERT INTO match (id, tenant_id, team_id, competition, opponent, court_id, match_date,
                                   is_home, status)
                VALUES (?, ?, ?, 'Campeonato', ?, ?, ?, true, 'SCHEDULED')
                ON CONFLICT (id) DO UPDATE SET team_id = EXCLUDED.team_id, opponent = EXCLUDED.opponent,
                    court_id = EXCLUDED.court_id, match_date = EXCLUDED.match_date,
                    status = 'SCHEDULED', anonymized_at = NULL
                """, matchId, tenantId, teamId, opponent, courtId, OffsetDateTime.parse(data));
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
