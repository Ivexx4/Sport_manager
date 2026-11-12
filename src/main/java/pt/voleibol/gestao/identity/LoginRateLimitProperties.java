package pt.voleibol.gestao.identity;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limitação de tentativas de login por clube + IP (proteção contra força bruta).
 *
 * <p>Só contam as falhas: ao atingir {@code max-attempts} dentro de {@code window}, a chave fica
 * bloqueada durante {@code block-duration}. Um login com sucesso limpa o contador.
 */
@ConfigurationProperties("app.security.login-rate-limit")
public record LoginRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("5m") Duration window,
        @DefaultValue("15m") Duration blockDuration,
        @DefaultValue("false") boolean trustForwardedHeaders,
        @DefaultValue("100000") int maxEntries,
        @DefaultValue("10m") Duration cleanupInterval) {

    public LoginRateLimitProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "app.security.login-rate-limit.max-attempts tem de ser maior ou igual a 1");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException(
                    "app.security.login-rate-limit.max-entries tem de ser maior ou igual a 1");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "app.security.login-rate-limit.window tem de ser uma duração positiva");
        }
        if (blockDuration == null || blockDuration.isZero() || blockDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "app.security.login-rate-limit.block-duration tem de ser uma duração positiva");
        }
        if (cleanupInterval == null || cleanupInterval.isZero() || cleanupInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "app.security.login-rate-limit.cleanup-interval tem de ser uma duração positiva");
        }
    }
}
