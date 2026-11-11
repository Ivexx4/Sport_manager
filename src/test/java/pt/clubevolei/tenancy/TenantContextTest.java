package pt.clubevolei.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
}
