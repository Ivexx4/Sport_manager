package pt.voleibol.gestao.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercita o caminho de limpeza sem nada a remover (com JdbcClient simulado). */
class ExpiredDataPurgerSemDadosTest {

    @Test
    void semDadosParaRemoverDevolveTudoZero() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ExpiredDataPurger.Purged> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.single()).thenReturn(new ExpiredDataPurger.Purged(0, 0, 0, 0));

        DataRetentionProperties properties = new DataRetentionProperties(
                true, Duration.ofHours(6), Duration.ofMinutes(1), Duration.ofDays(30),
                Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30));
        ExpiredDataPurger purger = new ExpiredDataPurger(jdbc, properties,
                Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC));

        assertThat(purger.purge()).isEqualTo(new ExpiredDataPurger.Purged(0, 0, 0, 0));
    }
}
