package pt.voleibol.gestao.billing;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Role;
import pt.voleibol.gestao.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingServiceUnitTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID ATHLETE = UUID.fromString("00000000-0000-0000-0000-000000000022");
    private static final UUID ADULT = UUID.fromString("00000000-0000-0000-0000-000000000023");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000024");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000025");

    @AfterEach
    void limpaTenantContext() {
        TenantContext.clear();
    }

    @Test
    void geraPlanosMensaisEContaDuplicados() {
        FeePlanRepository plans = mock(FeePlanRepository.class);
        ChargeRepository charges = mock(ChargeRepository.class);
        BillingService service = new BillingService(plans, charges, mock(PaymentRepository.class),
                mock(LedgerRepository.class));
        TenantContext.set(TENANT);

        FeePlanRepository.Row monthly = new FeePlanRepository.Row(PLAN, "Mensal", null, null, 1000,
                "MONTHLY", null, null);
        when(plans.list(TENANT)).thenReturn(List.of(monthly));
        when(charges.findTargets(TENANT, null, null)).thenReturn(List.of(
                new ChargeRepository.Target(ATHLETE, UUID.randomUUID(), LocalDate.now().minusYears(10)),
                new ChargeRepository.Target(ADULT, UUID.randomUUID(), LocalDate.now().minusYears(30))));
        when(charges.insertGenerated(any(), eq(TENANT), eq(ATHLETE), any(), eq(PLAN), eq(1000L),
                any(), anyString(), eq(null))).thenReturn(true);
        when(charges.insertGenerated(any(), eq(TENANT), eq(ADULT), eq(null), eq(PLAN), eq(1000L),
                any(), anyString(), eq(null))).thenReturn(false);

        BillingService.GenerateView result = service.generateChargesForTenant(TENANT, YearMonth.of(2026, 11), null);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        verify(charges).audit(eq(TENANT), eq(null), eq("CHARGE_GENERATED"), any());
    }

    @Test
    void dryRunDistingueExistentesECobreCadencias() {
        FeePlanRepository plans = mock(FeePlanRepository.class);
        ChargeRepository charges = mock(ChargeRepository.class);
        BillingService service = new BillingService(plans, charges, mock(PaymentRepository.class),
                mock(LedgerRepository.class));
        TenantContext.set(TENANT);

        FeePlanRepository.Row quarterly = new FeePlanRepository.Row(PLAN, "Trimestral", null, null, 1000,
                "QUARTERLY", null, null);
        FeePlanRepository.Row yearly = new FeePlanRepository.Row(UUID.randomUUID(), "Anual", null, null, 2000,
                "YEARLY", null, null);
        FeePlanRepository.Row oneTime = new FeePlanRepository.Row(UUID.randomUUID(), "Único", null, null, 3000,
                "ONE_TIME", null, null);
        when(plans.list(TENANT)).thenReturn(List.of(quarterly, yearly, oneTime));
        when(charges.findTargets(any(), any(), any())).thenReturn(List.of(
                new ChargeRepository.Target(ATHLETE, null, LocalDate.now().minusYears(10))));
        when(charges.generatedExists(any(), any(), eq(ATHLETE), anyString())).thenReturn(true);

        BillingService.GenerateView result = service.generateCharges(user(Role.FINANCEIRO), "2026-01", null, true);

        assertThat(result.period()).isEqualTo("2026-01");
        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(2);
    }

    @Test
    void periodoInvalidoERejeitado() {
        BillingService service = new BillingService(mock(FeePlanRepository.class), mock(ChargeRepository.class),
                mock(PaymentRepository.class), mock(LedgerRepository.class));
        TenantContext.set(TENANT);

        assertThatThrownBy(() -> service.generateCharges(user(Role.ADMIN), "2026-13", null, true))
                .isInstanceOfAny(RuntimeException.class);
    }

    private static CurrentUser user(Role... roles) {
        return new CurrentUser(ACTOR, Set.of(roles));
    }
}
