package pt.clubevolei.athlete;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pt.clubevolei.security.SecurityConfig;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AthleteController.class)
@Import(SecurityConfig.class)
class AthleteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AthleteRepository athleteRepository;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    @DisplayName("GET /api/athletes - Deve listar atletas autenticado com JWT e claim tenant_id")
    void shouldListAthletes() throws Exception {
        Athlete athlete = new Athlete("Maria Silva", LocalDate.of(2010, 5, 17));

        when(athleteRepository.findAll()).thenReturn(List.of(athlete));

        mockMvc.perform(get("/api/athletes")
                        .with(jwt().jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].fullName").value("Maria Silva"))
                .andExpect(jsonPath("$[0].birthDate").value("2010-05-17"));
    }

    @Test
    @DisplayName("POST /api/athletes - Deve criar um novo atleta quando DTO for válido")
    void shouldCreateAthlete() throws Exception {
        Athlete createdAthlete = new Athlete("João Souza", LocalDate.of(2011, 3, 2));

        when(athleteRepository.save(any(Athlete.class))).thenReturn(createdAthlete);

        mockMvc.perform(post("/api/athletes")
                        .with(jwt().jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"João Souza","birthDate":"2011-03-02"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("João Souza"))
                .andExpect(jsonPath("$.birthDate").value("2011-03-02"));
    }

    @Test
    @DisplayName("POST /api/athletes - Deve retornar Bad Request (400) quando nome for em branco")
    void shouldReturnBadRequestWhenNameIsEmpty() throws Exception {
        mockMvc.perform(post("/api/athletes")
                        .with(jwt().jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"","birthDate":"2011-03-02"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
