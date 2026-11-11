package pt.clubevolei.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pt.clubevolei.athlete.AthleteController;
import pt.clubevolei.athlete.AthleteRepository;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testa a cadeia de segurança sem levantar a base de dados.
 *
 * <p>O endpoint de health é representado por um controller de teste com o mesmo caminho,
 * porque o Actuator não faz parte da fatia MVC. O que está a ser validado é a regra do
 * {@link SecurityConfig}: health público, API protegida e JWT com tenant_id aceite.
 */
@WebMvcTest(controllers = {AthleteController.class, HealthStubController.class})
@Import(SecurityConfig.class)
class SecurityFilterChainTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AthleteRepository athleteRepository;

    @Test
    @DisplayName("GET /actuator/health - Deve permitir acesso público sem autenticação")
    void shouldAllowPublicAccessToHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("GET /api/athletes - Deve negar acesso (401 Unauthorized) sem token JWT")
    void shouldDenyAccessToApiWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/athletes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/athletes - Deve autorizar acesso (200 OK) com token JWT válido contendo tenant_id")
    void shouldAllowAccessToApiWithValidJwt() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/api/athletes")
                        .with(jwt().jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
                .andExpect(status().isOk());
    }
}
