package pt.clubevolei.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolve o tenant exclusivamente a partir do JWT já validado pelo Spring Security. */
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_CLAIM = "tenant_id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Não aplica a verificação de tenant a pedidos fora de /api/
        // ou a rotas públicas de autenticação (/api/v1/auth/**)
        return !path.startsWith("/api/") || path.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Token sem identidade de clube válida");
            return;
        }

        String rawTenantId = jwtAuthentication.getToken().getClaimAsString(TENANT_CLAIM);
        final UUID tenantId;
        try {
            if (rawTenantId == null || rawTenantId.isBlank()) {
                throw new IllegalArgumentException("Claim tenant_id em falta");
            }
            tenantId = UUID.fromString(rawTenantId);
        } catch (IllegalArgumentException ex) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Claim tenant_id em falta ou inválido");
            return;
        }

        TenantContext.set(tenantId);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}