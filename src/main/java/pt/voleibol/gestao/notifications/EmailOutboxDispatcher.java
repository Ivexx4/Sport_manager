package pt.voleibol.gestao.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Processa a outbox de e-mails com novas tentativas (backoff exponencial até ao limite). */
@Component
class EmailOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxDispatcher.class);
    private static final int BATCH_SIZE = 50;

    record Pending(UUID id, String recipient, String subject, String body, int attempts, int maxAttempts) {
    }

    private final JdbcClient jdbc;
    private final MailTransport transport;

    EmailOutboxDispatcher(JdbcClient jdbc, MailTransport transport) {
        this.jdbc = jdbc;
        this.transport = transport;
    }

    @Scheduled(fixedDelayString = "${app.mail.outbox.poll-interval:PT30S}")
    @Transactional
    public void dispatch() {
        List<Pending> batch = jdbc.sql("""
                        SELECT id, recipient, subject, body, attempts, max_attempts
                        FROM email_outbox
                        WHERE status = 'PENDING' AND next_attempt_at <= now()
                        ORDER BY next_attempt_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", BATCH_SIZE)
                .query((rs, i) -> new Pending(
                        rs.getObject("id", UUID.class),
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts")))
                .list();

        for (Pending mail : batch) {
            try {
                transport.deliver(mail.recipient(), mail.subject(), mail.body());
                markSent(mail.id());
            } catch (Exception e) {
                int attempts = mail.attempts() + 1;
                boolean failed = attempts >= mail.maxAttempts();
                Instant next = Instant.now().plus(backoff(attempts));
                String error = e.getMessage();
                if (error != null && error.length() > 500) {
                    error = error.substring(0, 500);
                }
                jdbc.sql("""
                                UPDATE email_outbox
                                SET attempts = :attempts,
                                    status = :status,
                                    next_attempt_at = :next,
                                    last_error = :error
                                WHERE id = :id
                                """)
                        .param("attempts", attempts)
                        .param("status", failed ? "FAILED" : "PENDING")
                        .param("next", java.time.OffsetDateTime.ofInstant(next, java.time.ZoneOffset.UTC))
                        .param("error", error)
                        .param("id", mail.id())
                        .update();
                if (failed) {
                    log.error("E-mail {} para {} falhou definitivamente após {} tentativas", mail.id(), mail.recipient(), attempts, e);
                } else {
                    log.warn("Falha ao enviar e-mail {} para {} (tentativa {}/{}), nova tentativa agendada",
                            mail.id(), mail.recipient(), attempts, mail.maxAttempts(), e);
                }
            }
        }
    }

    private void markSent(UUID id) {
        jdbc.sql("""
                        UPDATE email_outbox
                        SET status = 'SENT', sent_at = now(), last_error = NULL
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    private static Duration backoff(int attempts) {
        long seconds = Math.min(3600, 30L * (1L << Math.min(attempts, 6)));
        return Duration.ofSeconds(seconds);
    }
}
