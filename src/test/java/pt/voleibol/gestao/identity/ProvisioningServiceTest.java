package pt.voleibol.gestao.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Casos que falham antes de tocar na base de dados; o resto é coberto por ProvisioningFlowTest. */
class ProvisioningServiceTest {

    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Test
    void semChaveConfiguradaOProvisionamentoFicaDesativado() {
        ProvisioningService service = new ProvisioningService(null, ENCODER, "");

        assertThatThrownBy(() -> service.provisionTenant("qualquer", "Clube", "clube-x",
                "admin@clube-x.test", "Admin", "password-forte-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void chaveEmBrancoTambemDesativa() {
        ProvisioningService service = new ProvisioningService(null, ENCODER, "   ");

        assertThatThrownBy(() -> service.provisionTenant("", "Clube", "clube-x",
                "admin@clube-x.test", "Admin", "password-forte-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
