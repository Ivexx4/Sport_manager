package pt.voleibol.gestao.teams;

import com.jayway.jsonpath.JsonPath;

import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import pt.voleibol.gestao.shared.FlowTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Épocas e escalões (FPV) contra PostgreSQL real: CRUD, validações, leitura por qualquer membro,
 * escrita só para ADMIN/DIRECAO, isolamento entre clubes, remoção não destrutiva e associação às
 * equipas.
 */
class SeasonAgeGroupFlowTest extends FlowTestSupport {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registarPropriedades(registry);
    }

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000d201");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000d202");

    static final String HOST_A = "clube-epocas.localhost";
    static final String HOST_B = "clube-epocas-b.localhost";

    static final String ADMIN = "admin@epocas.test";
    static final String DIRECAO = "direcao@epocas.test";
    static final String TREINADOR = "treinador@epocas.test";
    static final String FINANCEIRO = "financeiro@epocas.test";
    static final String ADMIN_B = "admin@epocas-b.test";

    static final UUID EPOCA_A = UUID.fromString("00000000-0000-0000-0000-00000000d211");
    static final UUID EPOCA_B = UUID.fromString("00000000-0000-0000-0000-00000000d212");
    /** Escalões do catálogo federal, resolvidos na semeadura. */
    static UUID ESCALAO_A;
    static UUID ESCALAO_B;

    @BeforeEach
    void preparar() throws SQLException {
        semear();
    }

    @Test
    void adminCriaAtualizaListaEApagaEpocas() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criada = criarEpoca(HOST_A, admin, """
                {"name":"Época 2025/2026","startDate":"2025-08-01","endDate":"2026-07-31","isActive":true}
                """).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(criada, "$.id"));
        assertThat((Boolean) JsonPath.read(criada, "$.isActive")).isTrue();
        assertThat((String) JsonPath.read(criada, "$.startDate")).isEqualTo("2025-08-01");

        getEpoca(HOST_A, admin, id).andExpect(status().isOk());

        String lista = listarEpocas(HOST_A, admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(lista, "$[*].name"))
                .contains("Época A", "Época 2025/2026");

        criarEpoca(HOST_A, admin, """
                {"name":"época a","startDate":"2025-08-01"}
                """).andExpect(status().isConflict());
        criarEpoca(HOST_A, admin, """
                {"name":"Época inválida","startDate":"2026-08-01","endDate":"2025-08-01"}
                """).andExpect(status().isBadRequest());

        atualizarEpoca(HOST_A, admin, id, """
                {"name":"Época 2025/2026 - atual","isActive":false}
                """).andExpect(status().isOk());
        getEpoca(HOST_A, admin, id).andExpect(status().isOk());

        apagarEpoca(HOST_A, admin, id).andExpect(status().isNoContent());
        getEpoca(HOST_A, admin, id).andExpect(status().isNotFound());
        String depois = listarEpocas(HOST_A, admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(depois, "$[*].name"))
                .doesNotContain("Época 2025/2026 - atual");
        assertThat(contarAuditoria("SEASON_ANONYMIZED")).isPositive();

        // O nome fica livre para uma nova época.
        criarEpoca(HOST_A, admin, """
                {"name":"Época 2025/2026 - atual"}
                """).andExpect(status().isCreated());
    }

    @Test
    void leituraAbertaAEscritaSoParaGestao() throws Exception {
        mvc.perform(get("/api/v1/seasons").with(host(HOST_A))).andExpect(status().isUnauthorized());

        String financeiro = acesso(HOST_A, FINANCEIRO);
        listarEpocas(HOST_A, financeiro).andExpect(status().isOk());
        getEpoca(HOST_A, financeiro, EPOCA_A).andExpect(status().isOk());
        criarEpoca(HOST_A, financeiro, "{\"name\":\"Época do Financeiro\"}")
                .andExpect(status().isForbidden());

        String treinador = acesso(HOST_A, TREINADOR);
        listarEpocas(HOST_A, treinador).andExpect(status().isOk());
        getEpoca(HOST_A, treinador, EPOCA_A).andExpect(status().isOk());
        criarEpoca(HOST_A, treinador, "{\"name\":\"Época do Treinador\"}")
                .andExpect(status().isForbidden());
        atualizarEpoca(HOST_A, treinador, EPOCA_A, "{\"name\":\"Renomeada\"}")
                .andExpect(status().isForbidden());
        apagarEpoca(HOST_A, treinador, EPOCA_A).andExpect(status().isForbidden());

        listarEscaloes(HOST_A, treinador).andExpect(status().isOk());
        getEscalao(HOST_A, treinador, ESCALAO_A).andExpect(status().isOk());

        String direcao = acesso(HOST_A, DIRECAO);
        criarEpoca(HOST_A, direcao, "{\"name\":\"Época da Direção\"}").andExpect(status().isCreated());
    }

    @Test
    void catalogoFederalSoLeitura() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String lista = listarEscaloes(HOST_A, admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(lista, "$[*].name"))
                .contains("Minis A", "Cadetes", "Seniores", "Veteranos C");

        String cadetes = getEscalao(HOST_A, admin, ESCALAO_A).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(cadetes, "$.name")).isEqualTo("Cadetes");
        assertThat((Integer) JsonPath.read(cadetes, "$.minAge")).isEqualTo(15);
        assertThat((Integer) JsonPath.read(cadetes, "$.maxAge")).isEqualTo(15);
        getEscalao(HOST_A, admin, UUID.randomUUID()).andExpect(status().isNotFound());

        // O clube não escreve no catálogo: só existem GET.
        mvc.perform(post("/api/v1/age-groups").header("Authorization", "Bearer " + admin)
                        .with(host(HOST_A)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/v1/age-groups/" + ESCALAO_A).header("Authorization", "Bearer " + admin)
                        .with(host(HOST_A)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/v1/age-groups/" + ESCALAO_A)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void equipaAssociaEpocaEEscalao() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        String criada = mvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 A","seasonId":"%s","ageGroupId":"%s"}
                                """.formatted(EPOCA_A, ESCALAO_A)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID teamId = UUID.fromString(JsonPath.read(criada, "$.id"));
        assertThat((String) JsonPath.read(criada, "$.seasonId")).isEqualTo(EPOCA_A.toString());
        assertThat((String) JsonPath.read(criada, "$.ageGroupId")).isEqualTo(ESCALAO_A.toString());

        String atualizada = mvc.perform(put("/api/v1/teams/" + teamId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 A","seasonId":"%s","ageGroupId":"%s"}
                                """.formatted(EPOCA_A, ESCALAO_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(atualizada, "$.seasonId")).isEqualTo(EPOCA_A.toString());

        // PUT sem associações limpa os campos.
        String limpa = mvc.perform(put("/api/v1/teams/" + teamId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 A"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(limpa, "$.seasonId")).isNull();
        assertThat((Object) JsonPath.read(limpa, "$.ageGroupId")).isNull();

        // Qualquer escalão do catálogo federal é aceite, independentemente da época.
        mvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 B","seasonId":"%s","ageGroupId":"%s"}
                                """.formatted(EPOCA_A, ESCALAO_B)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 C","seasonId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        apagarEpoca(HOST_A, admin, EPOCA_A).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sub-18 D","seasonId":"%s"}
                                """.formatted(EPOCA_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void isolamentoEntreClubes() throws Exception {
        String adminA = acesso(HOST_A, ADMIN);
        String adminB = acesso(HOST_B, ADMIN_B);

        getEpoca(HOST_A, adminA, EPOCA_B).andExpect(status().isNotFound());
        atualizarEpoca(HOST_A, adminA, EPOCA_B, "{\"name\":\"Intrusa\"}")
                .andExpect(status().isNotFound());
        apagarEpoca(HOST_A, adminA, EPOCA_B).andExpect(status().isNotFound());

        String listaA = listarEpocas(HOST_A, adminA).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(listaA, "$[*].name")).doesNotContain("Época B");
        String listaB = listarEpocas(HOST_B, adminB).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(listaB, "$[*].name")).containsExactly("Época B");

        // O catálogo de escalões é federal: os dois clubes veem exatamente o mesmo.
        getEscalao(HOST_A, adminA, ESCALAO_B).andExpect(status().isOk());
        String escaloesA = listarEscaloes(HOST_A, adminA).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String escaloesB = listarEscaloes(HOST_B, adminB).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(escaloesA, "$[*].name")).contains("Cadetes", "Juvenis");
        assertThat(escaloesB).isEqualTo(escaloesA);
    }

    // --- auxiliares ---

    private ResultActions listarEpocas(String host, String token) throws Exception {
        return mvc.perform(get("/api/v1/seasons").header("Authorization", "Bearer " + token)
                .with(host(host)));
    }

    private ResultActions getEpoca(String host, String token, UUID seasonId) throws Exception {
        return mvc.perform(get("/api/v1/seasons/" + seasonId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions criarEpoca(String host, String token, String json) throws Exception {
        return mvc.perform(post("/api/v1/seasons").header("Authorization", "Bearer " + token)
                .with(host(host)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions atualizarEpoca(String host, String token, UUID seasonId, String json)
            throws Exception {
        return mvc.perform(put("/api/v1/seasons/" + seasonId)
                .header("Authorization", "Bearer " + token).with(host(host)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions apagarEpoca(String host, String token, UUID seasonId) throws Exception {
        return mvc.perform(delete("/api/v1/seasons/" + seasonId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions listarEscaloes(String host, String token) throws Exception {
        return mvc.perform(get("/api/v1/age-groups").header("Authorization", "Bearer " + token)
                .with(host(host)));
    }

    private ResultActions getEscalao(String host, String token, UUID ageGroupId) throws Exception {
        return mvc.perform(get("/api/v1/age-groups/" + ageGroupId)
                .header("Authorization", "Bearer " + token).with(host(host)));
    }

    // --- semeadura ---

    private void semear() throws SQLException {
        try (Connection c = superuser()) {
            // Mantém cada teste independente dentro do mesmo contentor.
            executar(c, "DELETE FROM team_athlete");
            executar(c, "DELETE FROM team_staff");
            executar(c, "DELETE FROM team");
            executar(c, "DELETE FROM season_promotion");
            executar(c, "DELETE FROM season");

            clube(c, CLUBE_A, "Clube Epocas", "clube-epocas");
            clube(c, CLUBE_B, "Clube Epocas B", "clube-epocas-b");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");
            membro(c, ADMIN_B, CLUBE_B, "ADMIN");

            epoca(c, EPOCA_A, CLUBE_A, "Época A");
            epoca(c, EPOCA_B, CLUBE_B, "Época B");
            ESCALAO_A = escalao(c, "CADETES");
            ESCALAO_B = escalao(c, "JUVENIS");
        }
    }



}
