package pt.voleibol.gestao.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import pt.voleibol.gestao.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChargeGeneratorTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final YearMonth PERIOD = YearMonth.of(2026, 11);
    /** Relógio fixo no período dos testes: o agendamento usa o mês da zona do clube. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneId.of("Europe/Lisbon"));

    @AfterEach
    void limpaTenantContext() {
        TenantContext.clear();
    }

    @Test
    void geraCobrancasNoTenantIndicado() {
        JdbcClient jdbc = mock(JdbcClient.class);
        BillingService billing = mock(BillingService.class);
        when(billing.generateChargesForTenant(TENANT, PERIOD, null))
                .thenReturn(new BillingService.GenerateView(PERIOD.toString(), 4, 1));

        ChargeGenerator generator = new ChargeGenerator(jdbc, billing, CLOCK);

        assertThat(generator.generateForTenant(TENANT, PERIOD)).isEqualTo(4);
        assertThat(TenantContext.current()).isEmpty();
        verify(billing).generateChargesForTenant(TENANT, PERIOD, null);
    }

    @Test
    void agendaProcessaTodosOsTenantsMesmoQuandoUmFalha() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<UUID> query = mock(JdbcClient.MappedQuerySpec.class);
        BillingService billing = mock(BillingService.class);
        UUID failingTenant = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID successfulTenant = UUID.fromString("00000000-0000-0000-0000-000000000003");

        when(jdbc.sql("SELECT id FROM tenant")).thenReturn(statement);
        when(statement.query(UUID.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of(failingTenant, successfulTenant));
        when(billing.generateChargesForTenant(eq(failingTenant), eq(PERIOD), eq(null)))
                .thenThrow(new IllegalStateException("falha simulada"));
        when(billing.generateChargesForTenant(eq(successfulTenant), eq(PERIOD), eq(null)))
                .thenReturn(new BillingService.GenerateView("2026-11", 2, 0));

        ChargeGenerator generator = new ChargeGenerator(jdbc, billing, CLOCK);
        generator.generateMonthlyCharges();

        verify(billing).generateChargesForTenant(eq(failingTenant), eq(PERIOD), eq(null));
        verify(billing).generateChargesForTenant(eq(successfulTenant), eq(PERIOD), eq(null));
        assertThat(TenantContext.current()).isEmpty();
    }
}
