package pt.voleibol.gestao.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    private static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    void callAsSemContextoAnteriorLimpaNoFim() {
        assertThat(TenantContext.current()).isEmpty();

        UUID visto = TenantContext.callAs(CLUBE_A, () -> TenantContext.current().orElseThrow());

        assertThat(visto).isEqualTo(CLUBE_A);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void callAsRepoeOContextoAnterior() {
        TenantContext.set(CLUBE_B);

        TenantContext.callAs(CLUBE_A, () -> {
            assertThat(TenantContext.current()).contains(CLUBE_A);
            return null;
        });

        assertThat(TenantContext.current()).contains(CLUBE_B);
    }

    @Test
    void callAsRepoeOContextoAnteriorMesmoComExcecao() {
        TenantContext.set(CLUBE_B);

        assertThatThrownBy(() -> TenantContext.callAs(CLUBE_A, () -> {
            throw new IllegalStateException("falha");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.current()).contains(CLUBE_B);
    }

    @Test
    void callAsComExcecaoSemContextoAnteriorLimpa() {
        assertThatThrownBy(() -> TenantContext.callAs(CLUBE_A, () -> {
            throw new IllegalStateException("falha");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void setEClearFuncionamComoEsperado() {
        TenantContext.set(CLUBE_A);
        assertThat(TenantContext.current()).contains(CLUBE_A);

        TenantContext.clear();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void callAsDevolveOResultadoDoTrabalho() {
        AtomicReference<Optional<UUID>> durante = new AtomicReference<>();

        String resultado = TenantContext.callAs(CLUBE_A, () -> {
            durante.set(TenantContext.current());
            return "ok";
        });

        assertThat(resultado).isEqualTo("ok");
        assertThat(durante.get()).contains(CLUBE_A);
    }
}
