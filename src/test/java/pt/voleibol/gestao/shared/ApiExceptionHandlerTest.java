package pt.voleibol.gestao.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** O formato de erro é contrato com o frontend: este teste fixa-o. */
class ApiExceptionHandlerTest {

    @RestController
    static class Sample {

        @GetMapping("/proibido")
        void forbidden() {
            throw Rules.forbidden("Sem papel para esta operação");
        }

        @GetMapping("/sem-detalhe")
        void forbiddenWithoutMessage() {
            throw Rules.forbidden();
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Sample())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void respostaDeErroSegueProblemJsonComDetalheDoServico() throws Exception {
        mvc.perform(get("/proibido"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Acesso negado"))
                .andExpect(jsonPath("$.detail").value("Sem papel para esta operação"))
                .andExpect(jsonPath("$.instance").value("/proibido"));
    }

    @Test
    void semMensagemUsaOTituloComoDetalhe() throws Exception {
        mvc.perform(get("/sem-detalhe"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Acesso negado"));
    }
}
