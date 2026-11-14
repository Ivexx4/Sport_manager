package pt.voleibol.gestao.billing;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Geração mensal de quotas a partir dos planos ({@code fee_plan}).
 *
 * <p>Corre no primeiro dia de cada mês, às 02:00, e processa cada clube na sua própria
 * transação. A idempotência é garantida pelo índice único parcial
 * {@code charge (tenant_id, fee_plan_id, athlete_id, billing_period)}: repetir a geração
 * do mesmo período não duplica cobranças.
 */
@Component
class ChargeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChargeGenerator.class);
    private static final ZoneId LISBOA = ZoneId.of("Europe/Lisbon");

    private final JdbcClient jdbc;
    private final BillingService billing;

    ChargeGenerator(JdbcClient jdbc, BillingService billing) {
        this.jdbc = jdbc;
        this.billing = billing;
    }

    @Scheduled(cron = "0 0 2 1 * *")
    public void generateMonthlyCharges() {
        YearMonth period = YearMonth.now(LISBOA);
        List<UUID> tenants = jdbc.sql("SELECT id FROM tenant").query(UUID.class).list();
        for (UUID tenantId : tenants) {
            try {
                generateForTenant(tenantId, period);
            } catch (Exception e) {
                log.warn("Falha ao gerar cobranças do período {} para o clube {}", period, tenantId, e);
            }
        }
    }

    /** Gera as cobranças do período para um clube; devolve quantas foram criadas. */
    int generateForTenant(UUID tenantId, YearMonth period) {
        int created = TenantContext.callAs(tenantId,
                () -> billing.generateChargesForTenant(tenantId, period, null).created());
        log.info("Cobranças geradas para o clube {} no período {}: {}", tenantId, period, created);
        return created;
    }
}
