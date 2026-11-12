package pt.voleibol.gestao.notifications;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Enfileira e-mails na outbox para envio assíncrono com novas tentativas. */
@Component
class OutboxEmailSender implements EmailSender {

    private final JdbcClient jdbc;

    OutboxEmailSender(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void send(String to, String subject, String textBody) {
        jdbc.sql("""
                        INSERT INTO email_outbox (recipient, subject, body, status, attempts, next_attempt_at)
                        VALUES (:to, :subject, :body, 'PENDING', 0, :now)
                        """)
                .param("to", to)
                .param("subject", subject)
                .param("body", textBody)
                .param("now", java.time.OffsetDateTime.ofInstant(Instant.now(), java.time.ZoneOffset.UTC))
                .update();
    }
}
