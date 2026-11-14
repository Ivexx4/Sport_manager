package pt.voleibol.gestao.maintenance;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataRetentionPropertiesTest {

    private static DataRetentionProperties valid() {
        return new DataRetentionProperties(true, Duration.ofHours(6), Duration.ofMinutes(1),
                Duration.ofDays(30), Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30));
    }

    @Test
    void valoresPorOmissaoSaoValidos() {
        DataRetentionProperties properties = valid();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.interval()).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void todasAsRetencoesTemDeSerPositivas() {
        assertThatThrownBy(() -> new DataRetentionProperties(
                true, null, Duration.ofMinutes(1), Duration.ofDays(30),
                Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval");
        assertThatThrownBy(() -> new DataRetentionProperties(
                true, Duration.ofHours(6), null, Duration.ofDays(30),
                Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial-delay");
        assertThatThrownBy(() -> new DataRetentionProperties(
                true, Duration.ofHours(6), Duration.ofMinutes(1), Duration.ZERO,
                Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invitation-retention");
        assertThatThrownBy(() -> new DataRetentionProperties(
                true, Duration.ofHours(6), Duration.ofMinutes(1), Duration.ofDays(30),
                Duration.ofSeconds(-1), Duration.ofDays(30), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password-reset-retention");
        assertThatThrownBy(() -> new DataRetentionProperties(
                true, Duration.ofHours(6), Duration.ofMinutes(1), Duration.ofDays(30),
                Duration.ofDays(7), null, Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refresh-token-retention");
        assertThatThrownBy(() -> new DataRetentionProperties(
                true, Duration.ofHours(6), Duration.ofMinutes(1), Duration.ofDays(30),
                Duration.ofDays(7), Duration.ofDays(30), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outbox-retention");
    }
}
