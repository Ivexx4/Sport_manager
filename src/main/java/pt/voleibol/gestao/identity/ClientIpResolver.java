package pt.voleibol.gestao.identity;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

/**
 * Endereço do cliente usado como chave do limitador de login.
 *
 * <p>Por omissão usa o endereço da ligação ({@code getRemoteAddr()}). Atrás de um proxy de confiança,
 * ativar {@code app.security.login-rate-limit.trust-forwarded-headers} para usar o primeiro valor de
 * {@code X-Forwarded-For} (o cliente original). Nunca ativar sem um proxy que escreva esse cabeçalho,
 * caso contrário qualquer cliente pode escolher o próprio IP.
 */
@Component
class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final boolean trustForwardedHeaders;

    ClientIpResolver(LoginRateLimitProperties properties) {
        this.trustForwardedHeaders = properties.trustForwardedHeaders();
    }

    String resolve(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String header = request.getHeader(FORWARDED_FOR);
            if (header != null) {
                for (String part : header.split(",")) {
                    String candidate = part.trim();
                    if (!candidate.isEmpty()) {
                        return candidate;
                    }
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
