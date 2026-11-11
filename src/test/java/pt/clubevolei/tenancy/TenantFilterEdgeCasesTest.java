package pt.clubevolei.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TenantFilterEdgeCasesTest {

    private TenantFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new TenantFilter();
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Deve responder 403 e não continuar a cadeia se o JWT não contiver o claim tenant_id")
    void shouldRejectJwtWithoutTenantIdClaim() throws Exception {
        authenticate(jwtWithoutTenantClaim());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/athletes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("Deve responder 403 e não continuar a cadeia se o tenant_id não for um UUID válido")
    void shouldRejectInvalidTenantIdClaim() throws Exception {
        authenticate(jwtWithTenantId("nao-e-um-uuid"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/athletes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("Deve garantir a limpeza do TenantContext via finally mesmo em caso de exceção no FilterChain")
    void shouldClearTenantContextOnFilterChainException() {
        UUID tenantId = UUID.randomUUID();
        authenticate(jwtWithTenantId(tenantId.toString()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/athletes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> {
            // O tenant está disponível durante o processamento do pedido.
            assertThat(TenantContext.require()).isEqualTo(tenantId);
            throw new ServletException("Erro na cadeia de filtros");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, throwingChain));

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("Deve ignorar pedidos fora de /api/ e não tocar no TenantContext")
    void shouldSkipNonApiRequests() throws Exception {
        authenticate(jwtWithTenantId(UUID.randomUUID().toString()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(TenantContext.current()).isEmpty();
    }

    private void authenticate(Jwt jwt) {
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Jwt jwtWithoutTenantClaim() {
        return Jwt.withTokenValue("mock-jwt-without-claim")
                .header("alg", "HS256")
                .claim("sub", "user123")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private Jwt jwtWithTenantId(String tenantId) {
        return Jwt.withTokenValue("mock-jwt")
                .header("alg", "HS256")
                .claim("sub", "user123")
                .claim(TenantFilter.TENANT_CLAIM, tenantId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
