package pt.clubevolei.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextConcurrencyTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Deve garantir isolamento de TenantContext entre threads concorrentes")
    void shouldMaintainTenantIsolationBetweenThreads() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<UUID> capturedInThreadB = executor.submit(() -> {
                TenantContext.set(tenantB);
                try {
                    return TenantContext.require();
                } finally {
                    TenantContext.clear();
                }
            });

            assertThat(capturedInThreadB.get(5, TimeUnit.SECONDS)).isEqualTo(tenantB);
        } finally {
            executor.shutdownNow();
        }

        // A thread principal mantém o seu tenant; o da outra thread não vazou.
        assertThat(TenantContext.current()).contains(tenantA);
    }
}
