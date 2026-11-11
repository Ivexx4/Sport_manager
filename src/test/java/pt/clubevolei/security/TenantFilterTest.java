package pt.clubevolei.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import pt.clubevolei.tenancy.TenantContext;
import pt.clubevolei.tenancy.TenantFilter;

class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void clearSecurityAndTenantContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void resolveTenantDoClaimJwtEIgnoraCabecalhoDoCliente() throws Exception {
        UUID claimTenant = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID forgedHeaderTenant = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        authenticateWithTenant(claimTenant);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/athletes");
        request.addHeader("X-Tenant-Id", forgedHeaderTenant.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> tenantSeenInsideRequest = new AtomicReference<>();
        FilterChain chain = (req, res) -> tenantSeenInsideRequest.set(TenantContext.require());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tenantSeenInsideRequest).hasValue(claimTenant);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void rejeitaTokenSemClaimTenantId() throws Exception {
        authenticateWithTenant(null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/athletes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainInvoked.set(true);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chainInvoked).isFalse();
        assertThat(TenantContext.current()).isEmpty();
    }

    private static void authenticateWithTenant(UUID tenantId) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("user-123")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (tenantId != null) {
            builder.claim(TenantFilter.TENANT_CLAIM, tenantId.toString());
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));
    }
}
