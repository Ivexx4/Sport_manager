package pt.voleibol.gestao.identity;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimitPropertiesTest {

    @Test
    void valoresPorOmissaoSaoValidos() {
        LoginRateLimitProperties properties = new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), Duration.ofMinutes(15), false, 100,
                Duration.ofMinutes(10));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.maxAttempts()).isEqualTo(5);
    }

    @Test
    void maxAttemptsTemDeSerPeloMenosUm() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 0, Duration.ofMinutes(5), Duration.ofMinutes(15), false, 100,
                Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
    }

    @Test
    void maxEntriesTemDeSerPeloMenosUm() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), Duration.ofMinutes(15), false, 0,
                Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-entries");
    }

    @Test
    void windowTemDeSerPositiva() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, null, Duration.ofMinutes(15), false, 100, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, Duration.ZERO, Duration.ofMinutes(15), false, 100, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, Duration.ofSeconds(-1), Duration.ofMinutes(15), false, 100,
                Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void blockDurationTemDeSerPositiva() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), null, false, 100, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block-duration");
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), Duration.ZERO, false, 100, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block-duration");
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), Duration.ofSeconds(-1), false, 100,
                Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block-duration");
    }

    @Test
    void cleanupIntervalTemDeSerPositivo() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), Duration.ofMinutes(15), false, 100, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-interval");
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), Duration.ofMinutes(15), false, 100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-interval");
        assertThatThrownBy(() -> new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), Duration.ofMinutes(15), false, 100,
                Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup-interval");
    }
}
