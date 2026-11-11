package pt.clubevolei.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Deve definir e recuperar o tenant ID corretamente no ThreadLocal")
    void shouldSetAndGetTenantId() {
        UUID tenantId = UUID.randomUUID();

        TenantContext.set(tenantId);

        assertThat(TenantContext.current()).contains(tenantId);
        assertThat(TenantContext.require()).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("Deve limpar o tenant ID do ThreadLocal")
    void shouldClearTenantId() {
        TenantContext.set(UUID.randomUUID());

        TenantContext.clear();

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("Deve falhar ao exigir tenant quando não existe nenhum no contexto")
    void shouldFailRequireWithoutTenant() {
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sem tenant no contexto");
    }

    @Test
    @DisplayName("callAs define o tenant durante a execução e limpa-o no fim")
    void callAsShouldClearContextWhenThereWasNoPreviousTenant() {
        UUID tenantId = UUID.randomUUID();

        UUID seen = TenantContext.callAs(tenantId, TenantContext::require);

        assertThat(seen).isEqualTo(tenantId);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("callAs restaura o tenant anterior no fim")
    void callAsShouldRestorePreviousTenant() {
        UUID outerTenant = UUID.randomUUID();
        UUID innerTenant = UUID.randomUUID();
        TenantContext.set(outerTenant);

        UUID seen = TenantContext.callAs(innerTenant, TenantContext::require);

        assertThat(seen).isEqualTo(innerTenant);
        assertThat(TenantContext.current()).contains(outerTenant);
    }

    @Test
    @DisplayName("callAs envolve exceções verificadas em IllegalStateException")
    void callAsShouldWrapCheckedExceptions() {
        UUID tenantId = UUID.randomUUID();
        Callable<Void> failingAction = () -> {
            throw new Exception("falha simulada");
        };

        assertThatThrownBy(() -> TenantContext.callAs(tenantId, failingAction))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(Exception.class);

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("runAs executa a ação com o tenant indicado e restaura o estado anterior")
    void runAsShouldRunWithTenantAndRestorePreviousState() {
        UUID outerTenant = UUID.randomUUID();
        UUID innerTenant = UUID.randomUUID();
        TenantContext.set(outerTenant);
        AtomicReference<UUID> seen = new AtomicReference<>();

        TenantContext.runAs(innerTenant, () -> seen.set(TenantContext.require()));

        assertThat(seen).hasValue(innerTenant);
        assertThat(TenantContext.current()).contains(outerTenant);
    }
}
