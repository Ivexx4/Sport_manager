package pt.voleibol.gestao.notifications;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;

class LoggingMailTransportTest {

    @Test
    void entregaApenasRegistandoEmLog() {
        // O construtor regista o aviso de ausência de SMTP; a entrega não pode falhar.
        LoggingMailTransport transport = new LoggingMailTransport();

        assertThatNoException().isThrownBy(
                () -> transport.deliver("atleta@example.pt", "Assunto", "Corpo"));
    }
}
