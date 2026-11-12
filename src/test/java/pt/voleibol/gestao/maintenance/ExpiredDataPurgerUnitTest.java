package pt.voleibol.gestao.maintenance;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Casos que não precisam de base de dados. O resto é coberto por {@link ExpiredDataPurgerTest}. */
class ExpiredDataPurgerUnitTest {

    @Test
    void desativadoNaoTocaNaBaseDeDados() {
        DataRetentionProperties properties = new DataRetentionProperties(
                false, Duration.ofHours(6), Duration.ofMinutes(1), Duration.ofDays(30),
                Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30));

        // jdbc nulo: se a guarda falhar, o teste rebenta em vez de passar.
        ExpiredDataPurger purger = new ExpiredDataPurger(null, properties, Clock.systemUTC());

        assertThat(purger.purge()).isEqualTo(new ExpiredDataPurger.Purged(0, 0, 0, 0));
    }

    @Test
    void retencoesTemDeSerPositivas() {
        assertThatThrownBy(() -> new DataRetentionProperties(
                true, Duration.ZERO, Duration.ofMinutes(1), Duration.ofDays(30),
                Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.maintenance.cleanup.interval");

        assertThatThrownBy(() -> new DataRetentionProperties(
                true, Duration.ofHours(6), Duration.ofMinutes(1), Duration.ofDays(-1),
                Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.maintenance.cleanup.invitation-retention");
    }
}
