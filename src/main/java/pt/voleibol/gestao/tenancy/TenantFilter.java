package pt.voleibol.gestao.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolve o tenant pelo subdomínio ({@code clube-x.<base-domain>}).
 *
 * <p>Corre antes da cadeia do Spring Security, para que o tenant já esteja definido quando o JWT é
 * validado: o claim tenant_id do token tem de coincidir com este tenant (ver TenantClaimValidator).
 * O pedido nunca escolhe o tenant sozinho.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends OncePerRequestFilter {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private final TenantResolver resolver;
    private final String baseDomain;

    TenantFilter(TenantResolver resolver, @Value("${app.tenancy.base-domain}") String baseDomain) {
        this.resolver = resolver;
        this.baseDomain = baseDomain.toLowerCase();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Optional<String> subdomain = extractSubdomain(request.getServerName());
            if (subdomain.isPresent()) {
                Optional<UUID> tenantId = subdomain
                        .filter(s -> SLUG.matcher(s).matches())
                        .flatMap(resolver::findActiveTenantId);
                if (tenantId.isEmpty()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                TenantContext.set(tenantId.get());
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<String> extractSubdomain(String host) {
        String suffix = "." + baseDomain;
        String normalized = host == null ? "" : host.toLowerCase();
        if (normalized.length() > suffix.length() && normalized.endsWith(suffix)) {
            return Optional.of(normalized.substring(0, normalized.length() - suffix.length()));
        }
        return Optional.empty();
    }
}
