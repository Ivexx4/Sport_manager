package pt.voleibol.gestao.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class LoginRateLimiterTest {

    private static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");
    private static final Instant AGORA = Instant.parse("2026-01-01T10:00:00Z");

    private final MutableClock clock = new MutableClock(AGORA);

    private LoginRateLimiter limiter(boolean enabled, int maxAttempts, Duration window,
                                     Duration blockDuration, int maxEntries) {
        return new LoginRateLimiter(new LoginRateLimitProperties(
                enabled, maxAttempts, window, blockDuration, false, maxEntries, Duration.ofMinutes(10)), clock);
    }

    @Test
    void bloqueiaAposMaximoDeFalhas() {
        LoginRateLimiter limiter = limiter(true, 3, Duration.ofMinutes(5), Duration.ofMinutes(15), 100);

        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isEmpty();

        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1"))
                .get()
                .satisfies(espera -> assertThat(espera).isEqualTo(Duration.ofMinutes(15)));
    }

    @Test
    void bloqueioExpiraEAceitaNovasTentativas() {
        LoginRateLimiter limiter = limiter(true, 3, Duration.ofMinutes(5), Duration.ofMinutes(15), 100);
        bloquear(limiter, CLUBE_A, "10.0.0.1");

        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isEmpty();

        // Depois do bloqueio, o cliente tem de novo o conjunto completo de tentativas.
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isEmpty();
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isPresent();
    }

    @Test
    void loginComSucessoLimpaOContador() {
        LoginRateLimiter limiter = limiter(true, 3, Duration.ofMinutes(5), Duration.ofMinutes(15), 100);
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.recordFailure(CLUBE_A, "10.0.0.1");

        limiter.recordSuccess(CLUBE_A, "10.0.0.1");

        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isEmpty();
    }

    @Test
    void contadoresSaoIndependentesPorClubeEIp() {
        LoginRateLimiter limiter = limiter(true, 3, Duration.ofMinutes(5), Duration.ofMinutes(15), 100);
        bloquear(limiter, CLUBE_A, "10.0.0.1");

        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isPresent();
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.2")).isEmpty();
        assertThat(limiter.retryAfter(CLUBE_B, "10.0.0.1")).isEmpty();
    }

    @Test
    void janelaExpiradaRecomecaAContagem() {
        LoginRateLimiter limiter = limiter(true, 3, Duration.ofMinutes(5), Duration.ofMinutes(15), 100);
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.recordFailure(CLUBE_A, "10.0.0.1");

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isEmpty();
    }

    @Test
    void desativadoNaoLimitaNemConta() {
        LoginRateLimiter limiter = limiter(false, 1, Duration.ofMinutes(5), Duration.ofMinutes(15), 100);

        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isEmpty();
    }

    @Test
    void semClubeNaoLimita() {
        LoginRateLimiter limiter = limiter(true, 1, Duration.ofMinutes(5), Duration.ofMinutes(15), 100);

        assertThatNoException().isThrownBy(() -> {
            limiter.recordFailure(null, "10.0.0.1");
            limiter.recordSuccess(null, "10.0.0.1");
        });
        assertThat(limiter.retryAfter(null, "10.0.0.1")).isEmpty();
    }

    @Test
    void limpezaRemoveApenasEntradasExpiradas() {
        LoginRateLimiter limiter = limiter(true, 3, Duration.ofMinutes(5), Duration.ofMinutes(15), 100);
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        bloquear(limiter, CLUBE_A, "10.0.0.2");

        // A primeira entrada sai da janela; o bloqueio da segunda continua ativo.
        clock.advance(Duration.ofMinutes(6));
        limiter.purgeExpired();

        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isEmpty();
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.2")).isPresent();
    }

    private static void bloquear(LoginRateLimiter limiter, UUID tenant, String ip) {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(tenant, ip);
        }
    }

    /** Relógio controlável: os testes não esperam pelo bloqueio real. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
