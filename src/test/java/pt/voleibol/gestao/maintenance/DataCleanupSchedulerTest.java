package pt.voleibol.gestao.maintenance;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DataCleanupSchedulerTest {

    @Test
    void disparaALimpezaPeriodica() {
        ExpiredDataPurger purger = mock(ExpiredDataPurger.class);
        DataCleanupScheduler scheduler = new DataCleanupScheduler(purger);

        scheduler.purgeExpiredData();

        verify(purger).purge();
    }
}
