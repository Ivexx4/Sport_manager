package pt.voleibol.gestao.notifications;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpMailTransportTest {

    @Test
    void enviaMensagemComRemetenteDestinatarioAssuntoECorpo() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpMailTransport transport = new SmtpMailTransport(mailSender, "clube@example.pt");

        transport.deliver("atleta@example.pt", "Bem-vindo", "Texto do convite");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("clube@example.pt");
        assertThat(message.getTo()).containsExactly("atleta@example.pt");
        assertThat(message.getSubject()).isEqualTo("Bem-vindo");
        assertThat(message.getText()).isEqualTo("Texto do convite");
    }
}
