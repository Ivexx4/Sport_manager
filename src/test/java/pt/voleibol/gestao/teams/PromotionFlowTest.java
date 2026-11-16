package pt.voleibol.gestao.teams;

import com.jayway.jsonpath.JsonPath;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import pt.voleibol.gestao.shared.FlowTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Autorizações excepcionais de subida de escalão: concessão, listagem, revogação e reativação com
 * auditoria, isolamento entre clubes e âmbito de papéis.
 */
class PromotionFlowTest extends FlowTestSupport {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registarPropriedades(registry);
    }

    static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000f301");
    static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000f302");

    static final String HOST_A = "clube-promocoes.localhost";
    static final String HOST_B = "clube-promocoes-b.localhost";

    static final String ADMIN = "admin@promocoes.test";
    static final String DIRECAO = "direcao@promocoes.test";
    static final String TREINADOR = "treinador@promocoes.test";
    static final String FINANCEIRO = "financeiro@promocoes.test";
    static final String ADMIN_B = "admin@promocoes-b.test";

    static final UUID EPOCA_A = UUID.fromString("00000000-0000-0000-0000-00000000f401");
    static final UUID EPOCA_B = UUID.fromString("00000000-0000-0000-0000-00000000f402");
    static UUID ESCALAO_A;
    static UUID ESCALAO_JUVENIS;

    static final UUID ATLETA_A = UUID.fromString("00000000-0000-0000-0000-00000000f501");
    static final UUID ATLETA_B = UUID.fromString("00000000-0000-0000-0000-00000000f502");
    static final UUID EQUIPA_A = UUID.fromString("00000000-0000-0000-0000-00000000f601");

    @BeforeEach
    void preparar() throws SQLException {
        semear();
    }

    @Test
    void adminConcedeListaRevogaEReativa() throws Exception {
        String admin = acesso(HOST_A, ADMIN);
        int concedidas = contarAuditoria("SEASON_PROMOTION_GRANTED");
        int revogadas = contarAuditoria("SEASON_PROMOTION_REVOKED");

        String criada = conceder(HOST_A, admin, EPOCA_A, ATLETA_A, ESCALAO_A, "Pedido do treinador")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reason").value("Pedido do treinador"))
                .andExpect(jsonPath("$.athleteId").value(ATLETA_A.toString()))
                .andExpect(jsonPath("$.athleteName").value("Atleta A"))
                .andExpect(jsonPath("$.ageGroupCode").value("CADETES"))
                .andExpect(jsonPath("$.authorizedBy").value(id(ADMIN).toString()))
                .andReturn().getResponse().getContentAsString();
        UUID promocaoId = UUID.fromString(JsonPath.read(criada, "$.id"));

        String lista = listar(HOST_A, admin, EPOCA_A, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(lista, "$[*].id")).containsExactly(promocaoId.toString());
        assertThat(JsonPath.<List<String>>read(lista, "$[*].status")).containsExactly("ACTIVE");

        String filtrada = listar(HOST_A, admin, EPOCA_A, ATLETA_B).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(filtrada, "$[*].id")).isEmpty();

        alterarEstado(HOST_A, admin, EPOCA_A, promocaoId, "REVOKED").andExpect(status().isNoContent());
        String revogada = listar(HOST_A, admin, EPOCA_A, ATLETA_A).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(revogada, "$[*].status")).containsExactly("REVOKED");

        // Reativar preserva o motivo original em vez de criar outra linha.
        conceder(HOST_A, admin, EPOCA_A, ATLETA_A, ESCALAO_A, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(promocaoId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reason").value("Pedido do treinador"));

        assertThat(contar("SELECT count(*) FROM season_promotion")).isEqualTo(1);
        assertThat(contarAuditoria("SEASON_PROMOTION_GRANTED")).isEqualTo(concedidas + 2);
        assertThat(contarAuditoria("SEASON_PROMOTION_REVOKED")).isEqualTo(revogadas + 1);
    }

    @Test
    void direcaoGereMasTreinadorEFinanceiroRecebem403() throws Exception {
        String direcao = acesso(HOST_A, DIRECAO);
        conceder(HOST_A, direcao, EPOCA_A, ATLETA_A, ESCALAO_A, null).andExpect(status().isCreated());

        for (String email : List.of(TREINADOR, FINANCEIRO)) {
            String token = acesso(HOST_A, email);
            listar(HOST_A, token, EPOCA_A, null).andExpect(status().isForbidden());
            conceder(HOST_A, token, EPOCA_A, ATLETA_A, ESCALAO_JUVENIS, null).andExpect(status().isForbidden());
            alterarEstado(HOST_A, token, EPOCA_A, UUID.randomUUID(), "REVOKED").andExpect(status().isForbidden());
        }
    }

    @Test
    void duplicadoAtivoEDevolvidoComoConflito() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        conceder(HOST_A, admin, EPOCA_A, ATLETA_A, ESCALAO_A, null).andExpect(status().isCreated());
        conceder(HOST_A, admin, EPOCA_A, ATLETA_A, ESCALAO_A, "Segunda tentativa")
                .andExpect(status().isConflict());
        assertThat(contar("SELECT count(*) FROM season_promotion")).isEqualTo(1);
    }

    @Test
    void referenciasDesconhecidasOuDeOutroClubeDao404() throws Exception {
        String admin = acesso(HOST_A, ADMIN);

        conceder(HOST_A, admin, UUID.randomUUID(), ATLETA_A, ESCALAO_A, null).andExpect(status().isNotFound());
        conceder(HOST_A, admin, EPOCA_A, UUID.randomUUID(), ESCALAO_A, null).andExpect(status().isNotFound());
        conceder(HOST_A, admin, EPOCA_A, ATLETA_A, UUID.randomUUID(), null).andExpect(status().isNotFound());
        // Atleta do outro clube.
        conceder(HOST_A, admin, EPOCA_A, ATLETA_B, ESCALAO_A, null).andExpect(status().isNotFound());

        // Época do outro clube: a mesma combinação de clube atleta/escalão não a encontra.
        conceder(HOST_A, admin, EPOCA_B, ATLETA_A, ESCALAO_A, null).andExpect(status().isNotFound());

        listar(HOST_A, admin, UUID.randomUUID(), null).andExpect(status().isNotFound());
        alterarEstado(HOST_A, admin, EPOCA_A, UUID.randomUUID(), "REVOKED").andExpect(status().isNotFound());
    }

    @Test
    void estadosInvalidosSaoRecusados() throws Exception {
        String admin = acesso(HOST_A, ADMIN);
        String criada = conceder(HOST_A, admin, EPOCA_A, ATLETA_A, ESCALAO_A, null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID promocaoId = UUID.fromString(JsonPath.read(criada, "$.id"));

        alterarEstado(HOST_A, admin, EPOCA_A, promocaoId, "PENDING").andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/seasons/" + EPOCA_A + "/promotions/" + promocaoId)
                        .header("Authorization", "Bearer " + admin).with(host(HOST_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void promocoesFicamIsoladasPorClube() throws Exception {
        String adminA = acesso(HOST_A, ADMIN);
        String adminB = acesso(HOST_B, ADMIN_B);
        conceder(HOST_A, adminA, EPOCA_A, ATLETA_A, ESCALAO_A, null).andExpect(status().isCreated());

        assertThat(JsonPath.<List<String>>read(
                listar(HOST_B, adminB, EPOCA_B, null).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$[*].id")).isEmpty();
        // A época do clube A não existe no clube B.
        listar(HOST_B, adminB, EPOCA_A, null).andExpect(status().isNotFound());
    }

    @Test
    void revogarNaoRemovePlantelNemInscricao() throws Exception {
        String admin = acesso(HOST_A, ADMIN);
        try (Connection c = superuser()) {
            executar(c, """
                    INSERT INTO team_athlete (tenant_id, team_id, athlete_id) VALUES (?, ?, ?)
                    ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                    """, CLUBE_A, EQUIPA_A, ATLETA_A);
            executar(c, """
                    INSERT INTO athlete_season_registration (tenant_id, season_id, athlete_id, age_group_id)
                    VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, season_id, athlete_id) DO NOTHING
                    """, CLUBE_A, EPOCA_A, ATLETA_A, ESCALAO_A);
        }

        String criada = conceder(HOST_A, admin, EPOCA_A, ATLETA_A, ESCALAO_A, null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        alterarEstado(HOST_A, admin, EPOCA_A, UUID.fromString(JsonPath.read(criada, "$.id")), "REVOKED")
                .andExpect(status().isNoContent());

        assertThat(contar("SELECT count(*) FROM team_athlete WHERE athlete_id = ?", ATLETA_A)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM athlete_season_registration WHERE athlete_id = ?", ATLETA_A))
                .isEqualTo(1);
    }

    // --- auxiliares ---

    private ResultActions listar(String host, String token, UUID seasonId, UUID athleteId) throws Exception {
        String url = "/api/v1/seasons/" + seasonId + "/promotions"
                + (athleteId == null ? "" : "?athleteId=" + athleteId);
        return mvc.perform(get(url).header("Authorization", "Bearer " + token).with(host(host)));
    }

    private ResultActions conceder(String host, String token, UUID seasonId, UUID athleteId, UUID ageGroupId,
                                   String reason) throws Exception {
        String corpo = reason == null
                ? "{\"athleteId\":\"" + athleteId + "\",\"ageGroupId\":\"" + ageGroupId + "\"}"
                : "{\"athleteId\":\"" + athleteId + "\",\"ageGroupId\":\"" + ageGroupId
                        + "\",\"reason\":\"" + reason + "\"}";
        return mvc.perform(post("/api/v1/seasons/" + seasonId + "/promotions")
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private ResultActions alterarEstado(String host, String token, UUID seasonId, UUID promotionId, String status)
            throws Exception {
        return mvc.perform(patch("/api/v1/seasons/" + seasonId + "/promotions/" + promotionId)
                .header("Authorization", "Bearer " + token).with(host(host))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    // --- semeadura ---

    private void semear() throws SQLException {
        try (Connection c = superuser()) {
            executar(c, "DELETE FROM team_athlete");
            executar(c, "DELETE FROM team");
            executar(c, "DELETE FROM season_promotion");
            executar(c, "DELETE FROM season");
            clube(c, CLUBE_A, "Clube Promoções", "clube-promocoes");
            clube(c, CLUBE_B, "Clube Promoções B", "clube-promocoes-b");

            membro(c, ADMIN, CLUBE_A, "ADMIN");
            membro(c, DIRECAO, CLUBE_A, "DIRECAO");
            membro(c, TREINADOR, CLUBE_A, "TREINADOR");
            membro(c, FINANCEIRO, CLUBE_A, "FINANCEIRO");
            membro(c, ADMIN_B, CLUBE_B, "ADMIN");

            atleta(c, ATLETA_A, CLUBE_A, "Atleta A", "2018-06-15", "F", null, null);
            atleta(c, ATLETA_B, CLUBE_B, "Atleta B", "2018-06-15", "F", null, null);

            epocaComCorte(c, EPOCA_A, CLUBE_A, "Época A");
            epocaComCorte(c, EPOCA_B, CLUBE_B, "Época B");
            ESCALAO_A = escalao(c, "CADETES");
            ESCALAO_JUVENIS = escalao(c, "JUVENIS");

            equipa(c, EQUIPA_A, CLUBE_A, "Equipa A", EPOCA_A, ESCALAO_A);
        }
    }
}
