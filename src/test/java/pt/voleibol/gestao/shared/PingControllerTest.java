package pt.voleibol.gestao.shared;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import pt.voleibol.gestao.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

class PingControllerTest {

    private final PingController controller = new PingController();

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    void devolveOkSemTenant() {
        assertThat(controller.ping())
                .containsEntry("status", "ok")
                .containsEntry("tenant", "nenhum");
    }

    @Test
    void devolveOTenantResolvido() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
        TenantContext.set(id);

        assertThat(controller.ping()).containsEntry("tenant", id.toString());
    }
}
