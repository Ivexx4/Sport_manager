package pt.clubevolei.tenancy;

import jakarta.servlet.FilterChain;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TenantFilterUnitTest {

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
    @DisplayName("Deve extrair tenant_id do JWT e definir no TenantContext")
    void shouldSetTenantContextFromJwt() throws Exception {
        UUID tenantId = UUID.randomUUID();

        Jwt jwt = Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "HS256")
                .claim("tenant_id", tenantId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/athletes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> tenantSeenInsideRequest = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            tenantSeenInsideRequest.set(TenantContext.require());
            filterChain.doFilter(req, res);
        };

        filter.doFilter(request, response, chain);

        verify(filterChain).doFilter(request, response);
        assertThat(tenantSeenInsideRequest).hasValue(tenantId);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("Deve ignorar requisições sem JWT sem lançar exceção")
    void shouldContinueFilterChainWithoutJwt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(TenantContext.current()).isEmpty();
    }
}
