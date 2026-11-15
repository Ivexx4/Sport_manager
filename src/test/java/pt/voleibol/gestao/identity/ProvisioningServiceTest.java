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

    @Test
    void geraChaveAleatoriaQueFicaAtiva() {
        ProvisioningService service = new ProvisioningService(null, ENCODER, "");

        String key = service.generateBootstrapKey();

        assertThat(key).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void validaChaveSlugNomeAdministradorEPasswordAntesDaBaseDeDados() {
        ProvisioningService service = new ProvisioningService(null, ENCODER, "bootstrap");

        assertThat(status(() -> service.provisionTenant(null, "Clube", "clube-x",
                "admin@clube-x.test", "Admin", "password-forte-1"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> service.provisionTenant("errada", "Clube", "clube-x",
                "admin@clube-x.test", "Admin", "password-forte-1"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> service.provisionTenant("bootstrap", "Clube", "Com Espaços",
                "admin@clube-x.test", "Admin", "password-forte-1"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(() -> service.provisionTenant("bootstrap", " ", "clube-x",
                "admin@clube-x.test", "Admin", "password-forte-1"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(() -> service.provisionTenant("bootstrap", "Clube", "clube-x",
                "", "Admin", "password-forte-1"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(() -> service.provisionTenant("bootstrap", "Clube", "clube-x",
                "admin@clube-x.test", "", "password-forte-1"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(() -> service.provisionTenant("bootstrap", "Clube", "clube-x",
                "admin@clube-x.test", "Admin", "curta"))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static HttpStatus status(org.junit.jupiter.api.function.Executable action) {
        try {
            action.execute();
            throw new AssertionError("Expected ResponseStatusException");
        } catch (ResponseStatusException e) {
            return (HttpStatus) e.getStatusCode();
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }
}
