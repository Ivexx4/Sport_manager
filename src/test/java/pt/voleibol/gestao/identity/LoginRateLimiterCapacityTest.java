package pt.voleibol.gestao.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Casos-limite do limitador: capacidade máxima, entradas já bloqueadas e limpeza de janelas ativas. */
class LoginRateLimiterCapacityTest {

    private static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private static final Instant AGORA = Instant.parse("2026-01-01T10:00:00Z");

    private final MutableClock clock = new MutableClock(AGORA);

    private LoginRateLimiter limiter(int maxAttempts, int maxEntries) {
        return new LoginRateLimiter(new LoginRateLimitProperties(
                true, maxAttempts, Duration.ofMinutes(5), Duration.ofMinutes(15), false, maxEntries,
                Duration.ofMinutes(10)), clock);
    }

    @Test
    void mapaCheioNaoContabilizaChavesNovas() {
        LoginRateLimiter limiter = limiter(3, 1);
        limiter.recordFailure(CLUBE_A, "10.0.0.1");

        // A chave nova não cabe e a limpeza não liberta a chave ativa.
        limiter.recordFailure(CLUBE_A, "10.0.0.2");

        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.2")).isEmpty();
        // A chave já registada continua a contar.
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isPresent();
    }

    @Test
    void mapaCheioAceitaChaveJaExistente() {
        LoginRateLimiter limiter = limiter(3, 1);
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.recordFailure(CLUBE_A, "10.0.0.1");

        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isEmpty();
    }

    @Test
    void falhasDepoisDoBloqueioNaoAlteramAEntrada() {
        LoginRateLimiter limiter = limiter(2, 100);
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isPresent();

        // Já bloqueado: a nova falha é ignorada e o bloqueio mantém-se.
        limiter.recordFailure(CLUBE_A, "10.0.0.1");

        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isPresent();
    }

    @Test
    void limpezaMantemJanelasAindaAtivas() {
        LoginRateLimiter limiter = limiter(3, 100);
        limiter.recordFailure(CLUBE_A, "10.0.0.1");

        clock.advance(Duration.ofMinutes(1));
        limiter.purgeExpired();

        // A janela continua aberta; a falha seguinte conta como segunda tentativa.
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isPresent();
    }

    @Test
    void mapaCheioLibertaEntradasExpiradasParaAChaveNova() {
        LoginRateLimiter limiter = limiter(3, 1);
        limiter.recordFailure(CLUBE_A, "10.0.0.1");

        // A janela da primeira chave expira; a limpeza abre espaço para a chave nova.
        clock.advance(Duration.ofMinutes(6));
        limiter.recordFailure(CLUBE_A, "10.0.0.2");
        limiter.recordFailure(CLUBE_A, "10.0.0.2");
        limiter.recordFailure(CLUBE_A, "10.0.0.2");

        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.2")).isPresent();
    }

    @Test
    void desativadoNaoLimpaNemConta() {
        LoginRateLimiter limiter = new LoginRateLimiter(new LoginRateLimitProperties(
                false, 3, Duration.ofMinutes(5), Duration.ofMinutes(15), false, 100,
                Duration.ofMinutes(10)), clock);

        limiter.recordFailure(CLUBE_A, "10.0.0.1");
        limiter.purgeExpired();

        assertThat(limiter.retryAfter(CLUBE_A, "10.0.0.1")).isEmpty();
    }

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
