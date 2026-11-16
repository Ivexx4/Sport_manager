package pt.voleibol.gestao.identity;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Aplica a limitação de tentativas aos passos da autenticação (password e segundo fator), para que
 * o bloqueio por clube + IP valha no conjunto e não só no primeiro pedido.
 */
@Component
class AuthThrottle {

    private final LoginRateLimiter limiter;
    private final ClientIpResolver clientIpResolver;

    AuthThrottle(LoginRateLimiter limiter, ClientIpResolver clientIpResolver) {
        this.limiter = limiter;
        this.clientIpResolver = clientIpResolver;
    }

    /** {@code 429} com {@code Retry-After} quando a chave está bloqueada. */
    Optional<ResponseEntity<Void>> blocked(HttpServletRequest request) {
        Optional<Duration> retryAfter = limiter.retryAfter(TenantContext.current().orElse(null), clientIp(request));
        if (retryAfter.isEmpty()) return Optional.empty();
        return Optional.of(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(retryAfter.get())))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build());
    }

    /** Sucesso limpa o contador; falha soma uma tentativa. */
    void record(HttpServletRequest request, boolean success) {
        UUID tenantId = TenantContext.current().orElse(null);
        String clientIp = clientIp(request);
        if (success) limiter.recordSuccess(tenantId, clientIp);
        else limiter.recordFailure(tenantId, clientIp);
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    private static long retryAfterSeconds(Duration duration) {
        return Math.max(1, (long) Math.ceil(duration.toMillis() / 1000.0));
    }
}
