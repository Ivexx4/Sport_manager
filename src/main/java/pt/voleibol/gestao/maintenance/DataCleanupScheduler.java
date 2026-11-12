package pt.voleibol.gestao.maintenance;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara a limpeza periódica de dados expirados. Fica separado do serviço para a chamada passar
 * pelo proxy transacional (uma autoinvocação não abriria a transação).
 */
@Component
class DataCleanupScheduler {

    private final ExpiredDataPurger purger;

    DataCleanupScheduler(ExpiredDataPurger purger) {
        this.purger = purger;
    }

    @Scheduled(fixedDelayString = "${app.maintenance.cleanup.interval:PT6H}",
            initialDelayString = "${app.maintenance.cleanup.initial-delay:PT1M}")
    public void purgeExpiredData() {
        purger.purge();
    }
}
