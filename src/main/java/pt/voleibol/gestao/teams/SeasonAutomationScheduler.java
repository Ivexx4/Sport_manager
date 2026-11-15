package pt.voleibol.gestao.teams;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Cria épocas a partir da configuração persistida em {@code season_template}. A operação é
 * idempotente para poder ser executada por mais do que uma instância da aplicação.
 */
@Component
class SeasonAutomationScheduler {
    private static final Logger log = LoggerFactory.getLogger(SeasonAutomationScheduler.class);

    private final JdbcClient jdbc;

    SeasonAutomationScheduler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 10 0 * * *")
    public void createConfiguredSeasons() {
        List<UUID> tenantIds = jdbc.sql("SELECT id FROM tenant WHERE status = 'ACTIVE'")
                .query(UUID.class).list();
        for (UUID tenantId : tenantIds) {
            try {
                TenantContext.callAs(tenantId, () -> {
                    createForTenant();
                    return null;
                });
            } catch (Exception e) {
                log.warn("Falha ao criar época automática para o clube {}", tenantId, e);
            }
        }
    }

    private void createForTenant() {
        List<Template> templates = jdbc.sql("""
                SELECT id, tenant_id, modality_id, name_pattern, start_month, start_day,
                       end_month, end_day, cutoff_month, cutoff_day
                FROM season_template
                WHERE auto_create AND is_active
                """).query((rs, row) -> new Template(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("modality_id", UUID.class), rs.getString("name_pattern"),
                rs.getInt("start_month"), rs.getInt("start_day"), rs.getInt("end_month"),
                rs.getInt("end_day"), rs.getInt("cutoff_month"), rs.getInt("cutoff_day"))).list();
        for (Template template : templates) {
            try {
                createIfMissing(template);
            } catch (Exception e) {
                log.warn("Falha ao criar época automática para o clube {}", template.tenantId(), e);
            }
        }
    }

    private void createIfMissing(Template template) {
        LocalDate today = LocalDate.now();
        int startYear = today.getMonthValue() >= template.startMonth()
                ? today.getYear() : today.getYear() - 1;
        int endYear = template.endMonth() > template.startMonth() ? startYear : startYear + 1;
        LocalDate start = LocalDate.of(startYear, template.startMonth(), template.startDay());
        LocalDate end = LocalDate.of(endYear, template.endMonth(), template.endDay());
        LocalDate cutoff = LocalDate.of(endYear, template.cutoffMonth(), template.cutoffDay());
        String name = template.namePattern().replace("{startYear}", String.valueOf(startYear))
                .replace("{endYear}", String.valueOf(endYear));
        UUID seasonId = jdbc.sql("""
                INSERT INTO season (tenant_id, modality_id, name, start_date, end_date, cutoff_date, is_active)
                SELECT :tenant, :modality, :name, :start, :end, :cutoff, true
                WHERE NOT EXISTS (
                    SELECT 1 FROM season WHERE tenant_id = :tenant AND lower(name) = lower(:name)
                    AND anonymized_at IS NULL)
                RETURNING id
                """).param("tenant", template.tenantId()).param("modality", template.modalityId())
                .param("name", name).param("start", start).param("end", end).param("cutoff", cutoff)
                .query(UUID.class).optional().orElse(null);
        if (seasonId != null) {
            log.info("Criada época automática {} para o clube {}", name, template.tenantId());
        }
    }

    private record Template(UUID id, UUID tenantId, UUID modalityId, String namePattern,
                            int startMonth, int startDay, int endMonth, int endDay,
                            int cutoffMonth, int cutoffDay) {
    }
}
