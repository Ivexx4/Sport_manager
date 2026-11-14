package pt.voleibol.gestao.training;

import com.jayway.jsonpath.JsonPath;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Treinos contra PostgreSQL real: CRUD de locais, sessões e presenças, validação, autorização por
 * papel (gestão, treinador da equipa, atleta/encarregado no plantel e financeiro só leitura),
 * isolamento entre clubes e remoção não destrutiva.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
class TrainingFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000d201");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000d202");

    static final String HOST_A = "clube-treinos.localhost";
    static final String HOST_B = "clube-treinos-b.localhost";
    static final String PASS = "palavra-passe-teste";

    static final String ADMIN = "admin@treinos.test";
    static final String DIRECAO = "direcao@treinos.test";
    static final String TREINADOR = "treinador@treinos.test";
    static final String FINANCEIRO = "financeiro@treinos.test";
    static final String ATLETA_USER = "atleta@treinos.test";
    static final String ENCARREGADO = "encarregado@treinos.test";
    static final String ADMIN_B = "admin@treinos-b.test";

    static final UUID EQUIPA_A = UUID.fromString("00000000-0000-0000-0000-00000000e201");
    static final UUID EQUIPA_OUTRA = UUID.fromString("00000000-0000-0000-0000-00000000e202");
    static final UUID EQUIPA_B = UUID.fromString("00000000-0000-0000-0000-00000000e203");

    static final UUID ATLETA_1 = UUID.fromString("00000000-0000-0000-0000-00000000f201");
    static final UUID ATLETA_2 = UUID.fromString("00000000-0000-0000-0000-00000000f202");
    static final UUID ATLETA_B = UUID.fromString("00000000-0000-0000-0000-00000000f203");

    static final UUID VENUE_A = UUID.fromString("00000000-0000-0000-0000-00000000ab01");
    static final UUID VENUE_B = UUID.fromString("00000000-0000-0000-0000-00000000ab02");

    static final UUID SESSAO_A = UUID.fromString("00000000-0000-0000-0000-00000000ac01");
    static final UUID SESSAO_OUTRA = UUID.fromString("00000000-0000-0000-0000-00000000ac02");
    static final UUID SESSAO_B = UUID.fromString("00000000-0000-0000-0000-00000000ac03");

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

    // --- locais ---

    @Test
    void locaisFluxoCompleto() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criado = criarLocal(HOST_A, admin, "{\"name\":\"Pavilhão Novo\",\"address\":\"Rua 1\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criado, "$.id"));
        assertThat((String) JsonPath.read(criado, "$.name")).isEqualTo("Pavilhão Novo");

        String detalhe = mvc.perform(get("/api/v1/venues/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(detalhe, "$.address")).isEqualTo("Rua 1");

        // O nome é único por clube sem distinguir maiúsculas.
        criarLocal(HOST_A, admin, "{\"name\":\"pavilhão novo\"}")
                .andExpect(status().isConflict());

        mvc.perform(put("/api/v1/venues/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pavilhão Renomeado\"}"))
                .andExpect(status().isOk());

        String lista = mvc.perform(get("/api/v1/venues")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lista)).contains(VENUE_A, id);

        mvc.perform(delete("/api/v1/venues/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/venues/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/venues/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        assertThat(contarAuditoria("VENUE_CREATED")).isPositive();
        assertThat(contarAuditoria("VENUE_UPDATED")).isPositive();
        assertThat(contarAuditoria("VENUE_ANONYMIZED")).isPositive();
    }

    // --- sessões e presenças ---

    @Test
    void sessoesEPresencasFluxoCompleto() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criada = criarSessao(HOST_A, admin, EQUIPA_A, VENUE_A,
                "2027-01-10T10:00:00Z", "2027-01-10T11:00:00Z", "ACTIVE", "Treino de manhã")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criada, "$.id"));

        String detalhe = mvc.perform(get("/api/v1/training-sessions/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(detalhe, "$.notes")).isEqualTo("Treino de manhã");

        mvc.perform(put("/api/v1/training-sessions/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessao(EQUIPA_A, VENUE_A, "2027-01-10T12:00:00Z", "2027-01-10T13:30:00Z",
                                "COMPLETED", "Treino concluído")))
                .andExpect(status().isOk());

        String porEquipa = mvc.perform(get("/api/v1/training-sessions").param("teamId", EQUIPA_A.toString())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(porEquipa)).contains(id, SESSAO_A).doesNotContain(SESSAO_OUTRA);

        // Presenças: upsert por POST e por PUT, listagem e remoção.
        adicionarPresenca(HOST_A, admin, id, ATLETA_1, "PRESENT", null)
                .andExpect(status().isNoContent());
        adicionarPresenca(HOST_A, admin, id, ATLETA_1, "LATE", "Trânsito")
                .andExpect(status().isNoContent());
        String presencas = mvc.perform(get("/api/v1/training-sessions/" + id + "/attendance")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(presencas, "$[*].status")).containsExactly("LATE");
        assertThat(JsonPath.<List<String>>read(presencas, "$[*].justification")).containsExactly("Trânsito");

        mvc.perform(put("/api/v1/training-sessions/" + id + "/attendance/" + ATLETA_1)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ABSENT\"}"))
                .andExpect(status().isNoContent());
        String depois = mvc.perform(get("/api/v1/training-sessions/" + id + "/attendance")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(depois, "$[*].status")).containsExactly("ABSENT");

        mvc.perform(delete("/api/v1/training-sessions/" + id + "/attendance/" + ATLETA_1)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/training-sessions/" + id + "/attendance/" + ATLETA_1)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/training-sessions/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/training-sessions/" + id)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        assertThat(contarAuditoria("TRAINING_SESSION_CREATED")).isPositive();
        assertThat(contarAuditoria("TRAINING_SESSION_UPDATED")).isPositive();
        assertThat(contarAuditoria("ATTENDANCE_SET")).isPositive();
        assertThat(contarAuditoria("ATTENDANCE_REMOVED")).isPositive();
    }

    @Test
    void remocaoDeSessaoPreservaPresencas() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        mvc.perform(delete("/api/v1/training-sessions/" + SESSAO_A)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());

        // A linha mantém-se, cancelada e anonimizada; as presenças ficam para histórico.
        try (Connection c = superuser(); PreparedStatement ps = c.prepareStatement(
                "SELECT status, anonymized_at FROM training_session WHERE id = ?")) {
            ps.setObject(1, SESSAO_A);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("CANCELLED");
                assertThat(rs.getObject("anonymized_at")).isNotNull();
            }
        }
        assertThat(contar("SELECT count(*) FROM attendance WHERE session_id = ? AND athlete_id = ?",
                SESSAO_A, ATLETA_1)).isEqualTo(1);

        mvc.perform(get("/api/v1/training-sessions/" + SESSAO_A)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/training-sessions/" + SESSAO_A + "/attendance")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        String lista = mvc.perform(get("/api/v1/training-sessions")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lista)).doesNotContain(SESSAO_A);
        assertThat(contarAuditoria("TRAINING_SESSION_ANONYMIZED")).isPositive();
    }

    // --- validação ---

    @Test
    void validacoesDeTreinos() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        mvc.perform(post("/api/v1/training-sessions").header("Authorization", "Bearer " + admin)
                        .with(host(HOST_A)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2027-01-10T10:00:00Z\",\"endTime\":\"2027-01-10T11:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        criarSessao(HOST_A, admin, EQUIPA_A, null,
                "2027-01-10T11:00:00Z", "2027-01-10T10:00:00Z", null, null)
                .andExpect(status().isBadRequest());
        criarSessao(HOST_A, admin, EQUIPA_A, null,
                "2027-01-10T10:00:00Z", "2027-01-10T10:00:00Z", null, null)
                .andExpect(status().isBadRequest());
        criarSessao(HOST_A, admin, EQUIPA_A, null,
                "2027-01-10T10:00:00Z", "2027-01-10T11:00:00Z", "BANIDO", null)
                .andExpect(status().isBadRequest());
        criarSessao(HOST_A, admin, EQUIPA_A, UUID.randomUUID(),
                "2027-01-10T10:00:00Z", "2027-01-10T11:00:00Z", null, null)
                .andExpect(status().isNotFound());
        criarSessao(HOST_A, admin, UUID.randomUUID(), null,
                "2027-01-10T10:00:00Z", "2027-01-10T11:00:00Z", null, null)
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/training-sessions/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/training-sessions/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessao(EQUIPA_A, null, "2027-01-10T10:00:00Z", "2027-01-10T11:00:00Z", null, null)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/training-sessions/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        adicionarPresenca(HOST_A, admin, SESSAO_A, ATLETA_1, "BANIDO", null)
                .andExpect(status().isBadRequest());
        adicionarPresenca(HOST_A, admin, SESSAO_A, ATLETA_B, "PRESENT", null)
                .andExpect(status().isBadRequest());
        adicionarPresenca(HOST_A, admin, UUID.randomUUID(), ATLETA_1, "PRESENT", null)
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/venues/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        criarLocal(HOST_A, admin, "{\"name\":\" \"}")
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/venues/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());
    }

    // --- autorização por papel ---

    @Test
    void autorizacaoPorPapelEmTreinos() throws Exception {
        String direcao = acesso(HOST_A, DIRECAO);
        assertThat(ids(mvc.perform(get("/api/v1/training-sessions")
                        .header("Authorization", "Bearer " + direcao).with(host(HOST_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()))
                .contains(SESSAO_A, SESSAO_OUTRA).doesNotContain(SESSAO_B);
        criarLocal(HOST_A, direcao, "{\"name\":\"Pavilhão da Direção\"}")
                .andExpect(status().isCreated());

        // FINANCEIRO lê locais, mas não vê treinos nem escreve.
        String financeiro = acesso(HOST_A, FINANCEIRO);
        mvc.perform(get("/api/v1/venues").header("Authorization", "Bearer " + financeiro)
                        .with(host(HOST_A)))
                .andExpect(status().isOk());
        criarLocal(HOST_A, financeiro, "{\"name\":\"Sem Permissão\"}")
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/training-sessions").header("Authorization", "Bearer " + financeiro)
                        .with(host(HOST_A)))
                .andExpect(status().isForbidden());

        // TREINADOR só vê e gere as equipas em que é staff.
        String treinador = acesso(HOST_A, TREINADOR);
        String listaTreinador = mvc.perform(get("/api/v1/training-sessions")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(listaTreinador)).containsExactly(SESSAO_A);
        mvc.perform(get("/api/v1/training-sessions/" + SESSAO_OUTRA)
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        criarSessao(HOST_A, treinador, EQUIPA_A, VENUE_A,
                "2027-02-01T10:00:00Z", "2027-02-01T11:00:00Z", null, null)
                .andExpect(status().isCreated());
        criarSessao(HOST_A, treinador, EQUIPA_OUTRA, VENUE_A,
                "2027-02-01T10:00:00Z", "2027-02-01T11:00:00Z", null, null)
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/training-sessions/" + SESSAO_A + "/attendance")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isOk());

        // ATLETA e ENCARREGADO vêem as sessões da equipa do atleta, mas não escrevem.
        for (String utilizador : List.of(acesso(HOST_A, ATLETA_USER), acesso(HOST_A, ENCARREGADO))) {
            String lista = mvc.perform(get("/api/v1/training-sessions")
                            .header("Authorization", "Bearer " + utilizador).with(host(HOST_A)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(ids(lista)).contains(SESSAO_A).doesNotContain(SESSAO_OUTRA);
            mvc.perform(get("/api/v1/training-sessions/" + SESSAO_A)
                            .header("Authorization", "Bearer " + utilizador).with(host(HOST_A)))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/training-sessions/" + SESSAO_OUTRA)
                            .header("Authorization", "Bearer " + utilizador).with(host(HOST_A)))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/training-sessions").param("teamId", EQUIPA_OUTRA.toString())
                            .header("Authorization", "Bearer " + utilizador).with(host(HOST_A)))
                    .andExpect(status().isNotFound());
            criarSessao(HOST_A, utilizador, EQUIPA_A, VENUE_A,
                    "2027-02-02T10:00:00Z", "2027-02-02T11:00:00Z", null, null)
                    .andExpect(status().isForbidden());
            adicionarPresenca(HOST_A, utilizador, SESSAO_A, ATLETA_1, "PRESENT", null)
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/training-sessions/" + SESSAO_A)
                            .header("Authorization", "Bearer " + utilizador).with(host(HOST_A)))
                    .andExpect(status().isForbidden());
            criarLocal(HOST_A, utilizador, "{\"name\":\"Local do Atleta\"}")
                    .andExpect(status().isForbidden());
        }
    }

    // --- isolamento entre clubes ---

    @Test
    void isolamentoEntreClubes() throws Exception {
        String adminA = acesso(HOST_A, ADMIN);
        String adminB = acesso(HOST_B, ADMIN_B);

        mvc.perform(get("/api/v1/venues/" + VENUE_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/training-sessions/" + SESSAO_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/training-sessions/" + SESSAO_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/training-sessions/" + SESSAO_B)
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessao(EQUIPA_A, null, "2027-03-01T10:00:00Z", "2027-03-01T11:00:00Z", null, null)))
                .andExpect(status().isNotFound());

        String listaA = mvc.perform(get("/api/v1/training-sessions")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(listaA)).contains(SESSAO_A, SESSAO_OUTRA).doesNotContain(SESSAO_B);
        String venuesA = mvc.perform(get("/api/v1/venues")
                        .header("Authorization", "Bearer " + adminA).with(host(HOST_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(venuesA)).contains(VENUE_A).doesNotContain(VENUE_B);

        mvc.perform(get("/api/v1/training-sessions/" + SESSAO_A)
                        .header("Authorization", "Bearer " + adminB).with(host(HOST_B)))
                .andExpect(status().isNotFound());
        String listaB = mvc.perform(get("/api/v1/training-sessions")
                        .header("Authorization", "Bearer " + adminB).with(host(HOST_B)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(listaB)).containsExactly(SESSAO_B);
    }

    @Test
    void pedidoSemAutenticacao() throws Exception {
        mvc.perform(get("/api/v1/training-sessions").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/venues").with(host(HOST_A)))
                .andExpect(status().isUnauthorized());
    }

    // --- auxiliares de pedido ---

    private ResultActions criarLocal(String host, String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/venues").header("Authorization", "Bearer " + token)
                .with(host(host)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions criarSessao(String host, String token, UUID teamId, UUID courtId,
                                      String start, String end, String status, String notes) throws Exception {
        return mvc.perform(post("/api/v1/training-sessions").header("Authorization", "Bearer " + token)
                .with(host(host)).contentType(MediaType.APPLICATION_JSON)
                .content(sessao(teamId, courtId, start, end, status, notes)));
    }

    private ResultActions adicionarPresenca(String host, String token, UUID sessionId, UUID athleteId,
                                            String status, String justification) throws Exception {
        return mvc.perform(post("/api/v1/training-sessions/" + sessionId + "/attendance")
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"athleteId\":\"" + athleteId + "\",\"status\":\"" + status
                        + "\",\"justification\":" + json(justification) + "}"));
    }

    private static String sessao(UUID teamId, UUID courtId, String start, String end, String status,
                                 String notes) {
        return "{\"teamId\":" + json(teamId) + ",\"courtId\":" + json(courtId)
                + ",\"startTime\":\"" + start + "\",\"endTime\":\"" + end
                + "\",\"status\":" + json(status) + ",\"notes\":" + json(notes) + "}";
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
            clube(c, CLUBE_A, "Clube Treinos", "clube-treinos");
            clube(c, CLUBE_B, "Clube Treinos B", "clube-treinos-b");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");
            membro(c, ATLETA_USER, CLUBE_A, "ATLETA");
            membro(c, ENCARREGADO, CLUBE_A, "ENCARREGADO");
            membro(c, ADMIN_B, CLUBE_B, "ADMIN");

            atleta(c, ATLETA_1, CLUBE_A, "Atleta Um", "2010-05-01", "F", "T-A1", id(ATLETA_USER));
            atleta(c, ATLETA_2, CLUBE_A, "Atleta Dois", "2010-05-02", "F", "T-A2", null);
            atleta(c, ATLETA_B, CLUBE_B, "Atleta de Outro Clube", "2010-05-03", "F", "T-B1", null);

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

            sessao(c, SESSAO_A, CLUBE_A, EQUIPA_A, VENUE_A, "2027-01-10T10:00:00Z");
            sessao(c, SESSAO_OUTRA, CLUBE_A, EQUIPA_OUTRA, null, "2027-01-11T10:00:00Z");
            sessao(c, SESSAO_B, CLUBE_B, EQUIPA_B, null, "2027-01-12T10:00:00Z");

            executar(c, """
                    INSERT INTO attendance (tenant_id, session_id, athlete_id, status)
                    VALUES (?, ?, ?, 'PRESENT')
                    ON CONFLICT (tenant_id, session_id, athlete_id) DO UPDATE SET status = EXCLUDED.status
                    """, CLUBE_A, SESSAO_A, ATLETA_1);
        }
    }

    private static void limpar(Connection c) throws SQLException {
        executar(c, "DELETE FROM attendance WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
        executar(c, "DELETE FROM training_session WHERE tenant_id IN (?, ?)", CLUBE_A, CLUBE_B);
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

    private static void sessao(Connection c, UUID sessionId, UUID tenantId, UUID teamId, UUID courtId,
                               String inicio) throws SQLException {
        OffsetDateTime start = OffsetDateTime.parse(inicio);
        executar(c, """
                INSERT INTO training_session (id, tenant_id, team_id, court_id, start_time, end_time, notes, status)
                VALUES (?, ?, ?, ?, ?, ?, 'Sessão semeada', 'ACTIVE')
                ON CONFLICT (id) DO UPDATE SET team_id = EXCLUDED.team_id, court_id = EXCLUDED.court_id,
                    start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time,
                    status = 'ACTIVE', anonymized_at = NULL
                """, sessionId, tenantId, teamId, courtId, start, start.plusHours(1));
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
