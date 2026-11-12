package pt.voleibol.gestao.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Transporte real via SMTP (ativo quando spring.mail.host está definido e não vazio).
 *
 * <p>A condição testa o valor: em staging é frequente a variável existir vazia (para usar o
 * transporte de log), e {@code @ConditionalOnProperty} consideraria esse caso como definido.
 */
@Component("smtpMailTransport")
@ConditionalOnExpression("'${spring.mail.host:}'.trim().length() > 0")
class SmtpMailTransport implements MailTransport {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpMailTransport(JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void deliver(String to, String subject, String textBody) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(textBody);
        mailSender.send(message);
    }
}
