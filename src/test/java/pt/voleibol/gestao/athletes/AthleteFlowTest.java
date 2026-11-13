package pt.voleibol.gestao.athletes;

import com.jayway.jsonpath.JsonPath;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * Fluxo de atletas contra PostgreSQL real, centrado na autorização por recurso:
 * ADMIN/DIRECAO em todo o clube, TREINADOR só nas suas equipas, ATLETA em si, ENCARREGADO nos
 * educandos e FINANCEIRO apenas leitura. Cobre também CRUD, validação, isolamento entre clubes,
 * encarregados, filtros/paginação e auditoria.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
class AthleteFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000a101");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000a102");

    static final String HOST_A = "clube-atletas.localhost";
    static final String HOST_B = "clube-atletas-b.localhost";

    static final String PASS = "palavra-passe-teste";

    static final String ADMIN = "admin@atletas.test";
    static final String DIRECAO = "direcao@atletas.test";
    static final String TREINADOR = "treinador@atletas.test";
    static final String FINANCEIRO = "financeiro@atletas.test";
    static final String ATLETA_USER = "atleta@atletas.test";
    static final String ENCARREGADO = "encarregado@atletas.test";
    static final String OUTRO_ATLETA = "outro-atleta@atletas.test";
    static final String ADMIN_B = "admin@atletas-b.test";

    static final UUID ATLETA_1 = UUID.fromString("00000000-0000-0000-0000-00000000b101");
    static final UUID ATLETA_2 = UUID.fromString("00000000-0000-0000-0000-00000000b102");
    static final UUID ATLETA_3 = UUID.fromString("00000000-0000-0000-0000-00000000b103");
    static final UUID ATLETA_4 = UUID.fromString("00000000-0000-0000-0000-00000000b104");
    static final UUID ATLETA_B = UUID.fromString("00000000-0000-0000-0000-00000000b105");

    static final UUID EQUIPA_A = UUID.fromString("00000000-0000-0000-0000-00000000c101");
    static final UUID EQUIPA_OUTRA = UUID.fromString("00000000-0000-0000-0000-00000000c102");

    /** O mesmo hash para todos os utilizadores de teste: evita calcular bcrypt dezenas de vezes. */
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

    // --- CRUD e validação ---

    @Test
    void adminCriaConsultaAtualizaEApaga() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criado = criar(HOST_A, admin, corpo("Novo Atleta", "2012-03-04", "F", null,
                null, null, "NOVA-1", null, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criado, "$.id"));
        assertThat((String) JsonPath.read(criado, "$.status")).isEqualTo("ACTIVE");

        String detalhe = getAtleta(HOST_A, admin, id)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(detalhe, "$.fullName")).isEqualTo("Novo Atleta");
        assertThat((String) JsonPath.read(detalhe, "$.birthDate")).isEqualTo("2012-03-04");

        atualizar(HOST_A, admin, id, corpo("Atleta Renomeado", "2012-03-04", "F", "123456789",
                "NOVO@EXEMPLO.PT", "912345678", "NOVA-1", null, null))
                .andExpect(status().isOk());
        String atualizado = getAtleta(HOST_A, admin, id)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(atualizado, "$.fullName")).isEqualTo("Atleta Renomeado");
        assertThat((String) JsonPath.read(atualizado, "$.email")).isEqualTo("novo@exemplo.pt");
        assertThat((String) JsonPath.read(atualizado, "$.nif")).isEqualTo("123456789");

        apagar(HOST_A, admin, id).andExpect(status().isNoContent());
        getAtleta(HOST_A, admin, id).andExpect(status().isNotFound());

        assertThat(contarAuditoria("ATHLETE_CREATED")).isPositive();
        assertThat(contarAuditoria("ATHLETE_UPDATED")).isPositive();
        assertThat(contarAuditoria("ATHLETE_DELETED")).isPositive();
    }

    @Test
    void validacaoDoPedido() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        criar(HOST_A, admin, corpo(null, "2012-03-04", "F", null, null, null, null, null, null))
                .andExpect(status().isBadRequest());
        criar(HOST_A, admin, corpo("Atleta", "2012-03-04", "X", null, null, null, null, null, null))
                .andExpect(status().isBadRequest());
        criar(HOST_A, admin, corpo("Atleta", LocalDate.now().plusDays(1).toString(), "F", null,
                null, null, null, null, null)).andExpect(status().isBadRequest());
        criar(HOST_A, admin, corpo("Atleta", "2012-03-04", "F", "123", null, null, null, null, null))
                .andExpect(status().isBadRequest());
        criar(HOST_A, admin, corpo("Atleta", "2012-03-04", "F", null, "invalido", null, null, null, null))
                .andExpect(status().isBadRequest());
        criar(HOST_A, admin, corpo("Atleta", "2012-03-04", "F", null, null, null, null, null, "BANIDO"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/athletes").with(host(HOST_A))).andExpect(status().isUnauthorized());
    }

    @Test
    void licencaDuplicadaDaConflito() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        criar(HOST_A, admin, corpo("Atleta A", "2010-01-01", "F", null, null, null, "LIC-DUP", null, null))
                .andExpect(status().isCreated());
        criar(HOST_A, admin, corpo("Atleta B", "2010-01-01", "F", null, null, null, "lic-dup", null, null))
                .andExpect(status().isConflict());
    }

    @Test
    void direcaoPodeGerir() throws Exception {
        String direcao = acesso(HOST_A, DIRECAO);

        String criado = criar(HOST_A, direcao, corpo("Atleta da Direcao", "2011-05-06", "M", null,
                null, null, "DIR-1", null, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criado, "$.id"));

        atualizar(HOST_A, direcao, id, corpo("Atleta da Direcao 2", "2011-05-06", "M", null,
                null, null, "DIR-1", null, null)).andExpect(status().isOk());
        apagar(HOST_A, direcao, id).andExpect(status().isNoContent());
    }

    // --- âmbito por papel ---

    @Test
    void financeiroLeMasNaoEscreve() throws Exception {
        String financeiro = acesso(HOST_A, FINANCEIRO);

        String lista = listar(HOST_A, financeiro).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lista)).contains(ATLETA_1, ATLETA_2, ATLETA_3, ATLETA_4);

        getAtleta(HOST_A, financeiro, ATLETA_1).andExpect(status().isOk());
        criar(HOST_A, financeiro, corpo("Sem Permissao", "2010-01-01", "F", null, null, null,
                "FIN-1", null, null)).andExpect(status().isForbidden());
        atualizar(HOST_A, financeiro, ATLETA_1, corpo("Tentativa", "2010-05-01", "F", null,
                null, null, "A1-LIC", null, null)).andExpect(status().isForbidden());
        apagar(HOST_A, financeiro, ATLETA_1).andExpect(status().isForbidden());
        mudarEstado(HOST_A, financeiro, ATLETA_1, "INACTIVE").andExpect(status().isForbidden());
        adicionarEncarregado(HOST_A, financeiro, ATLETA_1, id(OUTRO_ATLETA), "TUTOR")
                .andExpect(status().isForbidden());
    }

    @Test
    void treinadorSoVeEGereAtletasDasSuasEquipas() throws Exception {
        String treinador = acesso(HOST_A, TREINADOR);

        String lista = listar(HOST_A, treinador).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lista)).containsExactly(ATLETA_2);

        getAtleta(HOST_A, treinador, ATLETA_2).andExpect(status().isOk());
        getAtleta(HOST_A, treinador, ATLETA_1).andExpect(status().isNotFound());

        atualizar(HOST_A, treinador, ATLETA_2, corpo("Atleta da Equipa", "2010-05-02", "F", null,
                null, "933333333", "A2-LIC", null, null)).andExpect(status().isOk());
        atualizar(HOST_A, treinador, ATLETA_1, corpo("Intruso", "2010-05-01", "F", null,
                null, null, "A1-LIC", null, null)).andExpect(status().isNotFound());

        criar(HOST_A, treinador, corpo("Criado pelo Treinador", "2010-01-01", "F", null, null, null,
                "TRE-1", null, null)).andExpect(status().isForbidden());
        apagar(HOST_A, treinador, ATLETA_2).andExpect(status().isForbidden());
        mudarEstado(HOST_A, treinador, ATLETA_2, "INACTIVE").andExpect(status().isForbidden());

        // Equipas: só vê a sua e não pode gerir plantel/staff.
        String equipas = mvc.perform(get("/api/v1/teams")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(equipas, "$[*].name")).containsExactly("Equipa A");

        mvc.perform(get("/api/v1/teams/" + EQUIPA_OUTRA)
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/teams/" + EQUIPA_A + "/athletes")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/teams").header("Authorization", "Bearer " + treinador)
                        .with(host(HOST_A)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Equipa do Treinador\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/teams/" + EQUIPA_A + "/athletes")
                        .header("Authorization", "Bearer " + treinador).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"athleteId\":\"" + ATLETA_1 + "\"}"))
                .andExpect(status().isForbidden());

        // Ao sair da equipa, perde o acesso ao atleta.
        String admin = acesso(HOST_A, ADMIN);
        mvc.perform(delete("/api/v1/teams/" + EQUIPA_A + "/athletes/" + ATLETA_2)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        getAtleta(HOST_A, treinador, ATLETA_2).andExpect(status().isNotFound());
    }

    @Test
    void atletaSoVeEGereORegistroProprio() throws Exception {
        String atleta = acesso(HOST_A, ATLETA_USER);

        String lista = listar(HOST_A, atleta).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lista)).containsExactly(ATLETA_1);

        getAtleta(HOST_A, atleta, ATLETA_1).andExpect(status().isOk());
        getAtleta(HOST_A, atleta, ATLETA_2).andExpect(status().isNotFound());

        atualizar(HOST_A, atleta, ATLETA_1, corpo("O Meu Nome", "2010-05-01", "F", null,
                "eu@exemplo.pt", "911111111", "A1-LIC", null, null)).andExpect(status().isOk());
        atualizar(HOST_A, atleta, ATLETA_2, corpo("Outro", "2010-05-02", "F", null,
                null, null, "A2-LIC", null, null)).andExpect(status().isNotFound());

        criar(HOST_A, atleta, corpo("Novo", "2010-01-01", "F", null, null, null, "ATL-1",
                null, null)).andExpect(status().isForbidden());
        apagar(HOST_A, atleta, ATLETA_1).andExpect(status().isForbidden());
        mudarEstado(HOST_A, atleta, ATLETA_1, "INACTIVE").andExpect(status().isForbidden());
        adicionarEncarregado(HOST_A, atleta, ATLETA_1, id(OUTRO_ATLETA), "TUTOR")
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/athletes/" + ATLETA_1 + "/guardians")
                        .header("Authorization", "Bearer " + atleta).with(host(HOST_A)))
                .andExpect(status().isOk());
    }

    @Test
    void encarregadoSoVeEGereOsEducandos() throws Exception {
        String encarregado = acesso(HOST_A, ENCARREGADO);

        String lista = listar(HOST_A, encarregado).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(lista)).containsExactly(ATLETA_3);

        getAtleta(HOST_A, encarregado, ATLETA_3).andExpect(status().isOk());
        getAtleta(HOST_A, encarregado, ATLETA_1).andExpect(status().isNotFound());
        atualizar(HOST_A, encarregado, ATLETA_3, corpo("Educando", "2010-05-03", "F", null,
                null, "922222222", "A3-LIC", null, null)).andExpect(status().isOk());
        atualizar(HOST_A, encarregado, ATLETA_1, corpo("Intruso", "2010-05-01", "F", null,
                null, null, "A1-LIC", null, null)).andExpect(status().isNotFound());

        criar(HOST_A, encarregado, corpo("Novo", "2010-01-01", "F", null, null, null, "ENC-1",
                null, null)).andExpect(status().isForbidden());
        apagar(HOST_A, encarregado, ATLETA_3).andExpect(status().isForbidden());

        String guardioes = mvc.perform(get("/api/v1/athletes/" + ATLETA_3 + "/guardians")
                        .header("Authorization", "Bearer " + encarregado).with(host(HOST_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(guardioes, "$[*].email")).containsExactly(ENCARREGADO);
    }

    // --- encarregados ---

    @Test
    void gestaoDeEncarregados() throws Exception {
        String admin = acesso(HOST_A, ADMIN);
        String outro = acesso(HOST_A, OUTRO_ATLETA);

        getAtleta(HOST_A, outro, ATLETA_4).andExpect(status().isNotFound());

        adicionarEncarregado(HOST_A, admin, ATLETA_4, id(OUTRO_ATLETA), "TUTOR")
                .andExpect(status().isOk());
        adicionarEncarregado(HOST_A, admin, ATLETA_4, id(OUTRO_ATLETA), "TUTOR")
                .andExpect(status().isConflict());

        getAtleta(HOST_A, outro, ATLETA_4).andExpect(status().isOk());
        atualizar(HOST_A, outro, ATLETA_4, corpo("Educando 4", "2010-05-04", "F", null,
                null, null, "A4-LIC", null, null)).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/athletes/" + ATLETA_4 + "/guardians/" + id(OUTRO_ATLETA))
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNoContent());
        getAtleta(HOST_A, outro, ATLETA_4).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/athletes/" + ATLETA_4 + "/guardians/" + id(OUTRO_ATLETA))
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isNotFound());

        adicionarEncarregado(HOST_A, admin, ATLETA_4, UUID.randomUUID(), "TUTOR")
                .andExpect(status().isNotFound());
        adicionarEncarregado(HOST_A, admin, ATLETA_4, id(ADMIN_B), "TUTOR")
                .andExpect(status().isNotFound());

        String treinador = acesso(HOST_A, TREINADOR);
        adicionarEncarregado(HOST_A, treinador, ATLETA_2, id(OUTRO_ATLETA), "TUTOR")
                .andExpect(status().isForbidden());

        assertThat(contarAuditoria("ATHLETE_GUARDIAN_ADDED")).isPositive();
        assertThat(contarAuditoria("ATHLETE_GUARDIAN_REMOVED")).isPositive();
    }

    @Test
    void ligacaoEContagemDeEstadoSaoExclusivasDaGestao() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        mvc.perform(post("/api/v1/athletes/" + ATLETA_2 + "/account")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + id(ATLETA_USER) + "\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/athletes/" + ATLETA_4 + "/account")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + id(OUTRO_ATLETA) + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/athletes/" + ATLETA_4 + "/account")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk());

        mudarEstado(HOST_A, admin, ATLETA_2, "INACTIVE").andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM athlete WHERE id = ? AND status = 'INACTIVE'", ATLETA_2))
                .isEqualTo(1);

        String atleta = acesso(HOST_A, ATLETA_USER);
        mudarEstado(HOST_A, atleta, ATLETA_1, "INACTIVE").andExpect(status().isForbidden());
    }

    // --- isolamento, filtros e paginação ---

    @Test
    void isolamentoEntreClubes() throws Exception {
        String adminA = acesso(HOST_A, ADMIN);
        String adminB = acesso(HOST_B, ADMIN_B);

        getAtleta(HOST_A, adminA, ATLETA_B).andExpect(status().isNotFound());
        atualizar(HOST_A, adminA, ATLETA_B, corpo("Intruso", "2010-05-05", "F", null,
                null, null, "A1-LIC", null, null)).andExpect(status().isNotFound());
        apagar(HOST_A, adminA, ATLETA_B).andExpect(status().isNotFound());

        String listaA = listar(HOST_A, adminA).andReturn().getResponse().getContentAsString();
        assertThat(ids(listaA)).doesNotContain(ATLETA_B);
        String listaB = listar(HOST_B, adminB).andReturn().getResponse().getContentAsString();
        assertThat(ids(listaB)).containsExactly(ATLETA_B);

        getAtleta(HOST_B, adminB, ATLETA_1).andExpect(status().isNotFound());
    }

    @Test
    void pesquisaFiltrosEPaginacao() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String resposta = mvc.perform(get("/api/v1/athletes")
                        .param("q", "Equipa")
                        .param("teamId", EQUIPA_A.toString())
                        .param("page", "0").param("size", "1")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(resposta, "$.total")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(resposta, "$.items[*].id")).containsExactly(ATLETA_2.toString());

        String inativos = mvc.perform(get("/api/v1/athletes").param("status", "INACTIVE")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(inativos, "$.items[*].id")).isEmpty();

        mvc.perform(get("/api/v1/athletes").param("size", "101")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isBadRequest());
    }

    // --- auxiliares de pedido ---

    private ResultActions listar(String host, String token) throws Exception {
        return mvc.perform(get("/api/v1/athletes").header("Authorization", "Bearer " + token)
                .with(host(host)));
    }

    private ResultActions getAtleta(String host, String token, UUID athleteId) throws Exception {
        return mvc.perform(get("/api/v1/athletes/" + athleteId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions criar(String host, String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/athletes").header("Authorization", "Bearer " + token)
                .with(host(host)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions atualizar(String host, String token, UUID athleteId, String body) throws Exception {
        return mvc.perform(put("/api/v1/athletes/" + athleteId)
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions apagar(String host, String token, UUID athleteId) throws Exception {
        return mvc.perform(delete("/api/v1/athletes/" + athleteId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions mudarEstado(String host, String token, UUID athleteId, String status)
            throws Exception {
        return mvc.perform(patch("/api/v1/athletes/" + athleteId + "/status")
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions adicionarEncarregado(String host, String token, UUID athleteId, UUID userId,
                                               String relationship) throws Exception {
        return mvc.perform(post("/api/v1/athletes/" + athleteId + "/guardians")
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"relationship\":\"" + relationship + "\"}"));
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

    private static String corpo(String nome, String nascimento, String genero, String nif, String email,
                                String telefone, String licenca, String userId, String status) {
        StringBuilder body = new StringBuilder("{")
                .append("\"fullName\":").append(json(nome))
                .append(",\"birthDate\":").append(json(nascimento))
                .append(",\"gender\":").append(json(genero))
                .append(",\"nif\":").append(json(nif))
                .append(",\"email\":").append(json(email))
                .append(",\"phone\":").append(json(telefone))
                .append(",\"licenseNumber\":").append(json(licenca));
        if (userId != null) {
            body.append(",\"userId\":").append(json(userId));
        }
        if (status != null) {
            body.append(",\"status\":").append(json(status));
        }
        return body.append('}').toString();
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static List<UUID> ids(String json) {
        List<String> valores = JsonPath.read(json, "$.items[*].id");
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

    /** Clubes, utilizadores, atletas, equipas e relações de teste. Idempotente. */
    private void semear() throws SQLException {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASS);
        }
        try (Connection c = superuser()) {
            clube(c, CLUBE_A, "Clube Atletas", "clube-atletas");
            clube(c, CLUBE_B, "Clube Atletas B", "clube-atletas-b");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");
            membro(c, ATLETA_USER, CLUBE_A, "ATLETA");
            membro(c, ENCARREGADO, CLUBE_A, "ENCARREGADO");
            membro(c, OUTRO_ATLETA, CLUBE_A, "ATLETA", "ENCARREGADO");
            membro(c, ADMIN_B, CLUBE_B, "ADMIN");

            atleta(c, ATLETA_1, CLUBE_A, "Atleta Um", "2010-05-01", "F", "A1-LIC", id(ATLETA_USER));
            atleta(c, ATLETA_2, CLUBE_A, "Atleta da Equipa", "2010-05-02", "F", "A2-LIC", null);
            atleta(c, ATLETA_3, CLUBE_A, "Atleta Educando", "2010-05-03", "F", "A3-LIC", null);
            atleta(c, ATLETA_4, CLUBE_A, "Atleta Quatro", "2010-05-04", "F", "A4-LIC", null);
            atleta(c, ATLETA_B, CLUBE_B, "Atleta de Outro Clube", "2010-05-05", "F", "B1-LIC", null);

            equipa(c, EQUIPA_A, CLUBE_A, "Equipa A");
            equipa(c, EQUIPA_OUTRA, CLUBE_A, "Equipa B");
            executar(c, """
                    INSERT INTO team_staff (tenant_id, team_id, user_id, role)
                    VALUES (?, ?, ?, 'TREINADOR') ON CONFLICT (tenant_id, team_id, user_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, id(TREINADOR));
            executar(c, """
                    INSERT INTO team_athlete (tenant_id, team_id, athlete_id)
                    VALUES (?, ?, ?) ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, ATLETA_2);
            executar(c, """
                    INSERT INTO athlete_guardian (tenant_id, athlete_id, user_id, relationship)
                    VALUES (?, ?, ?, 'MAE') ON CONFLICT (tenant_id, athlete_id, user_id) DO NOTHING
                    """, CLUBE_A, ATLETA_3, id(ENCARREGADO));
        }
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
                    license_number = EXCLUDED.license_number, status = 'ACTIVE', user_id = EXCLUDED.user_id
                """, athleteId, tenantId, nome, Date.valueOf(nascimento), genero, licenca, userId);
    }

    private static void equipa(Connection c, UUID teamId, UUID tenantId, String nome) throws SQLException {
        executar(c, """
                INSERT INTO team (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE'
                """, teamId, tenantId, nome);
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
