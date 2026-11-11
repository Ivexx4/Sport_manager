package pt.clubevolei.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import pt.clubevolei.dto.AuthDto.LoginRequest;
import pt.clubevolei.security.JwtTokenService;
import pt.clubevolei.security.SecurityConfig;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtTokenService.class})
class AuthControllerTest {

    private static final String VALID_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should login successfully with valid credentials and return JWT token")
    void shouldLoginSuccessfully() throws Exception {
        LoginRequest request = new LoginRequest("admin@clube.pt", "123456", VALID_TENANT_ID);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.expiresIn", notNullValue()));
    }

    @Test
    @DisplayName("Should return 401 Unauthorized for invalid password")
    void shouldReturn401ForWrongPassword() throws Exception {
        LoginRequest request = new LoginRequest("admin@clube.pt", "wrongpassword", VALID_TENANT_ID);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 401 Unauthorized for unknown user")
    void shouldReturn401ForUnknownUser() throws Exception {
        LoginRequest request = new LoginRequest("unknown@clube.pt", "123456", VALID_TENANT_ID);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 400 Bad Request when tenantId is empty")
    void shouldReturn400WhenTenantIdIsEmpty() throws Exception {
        LoginRequest request = new LoginRequest("admin@clube.pt", "123456", "");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 400 Bad Request when request body is missing fields")
    void shouldReturn400WhenBodyIsInvalid() throws Exception {
        LoginRequest request = new LoginRequest(null, "123456", VALID_TENANT_ID);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}