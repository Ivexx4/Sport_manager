package pt.clubevolei.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ATENÇÃO: versão provisória da fase 0. O tenant vem do cabeçalho X-Tenant-Id, o que NÃO é seguro
 * em produção (qualquer cliente escolhia o seu clube). Será substituído por: tenant do subdomínio
 * validado contra o claim tenant_id do JWT, assim que a autenticação existir.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Tenant-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        UUID tenantId;
        try {
            tenantId = UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Tenant em falta ou inválido");
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
