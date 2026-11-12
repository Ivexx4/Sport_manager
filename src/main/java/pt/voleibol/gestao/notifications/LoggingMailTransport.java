package pt.voleibol.gestao.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Transporte por defeito quando não há SMTP configurado: regista o destinatário e o assunto.
 * O corpo (que contém links com tokens) só aparece com o nível DEBUG (perfil dev).
 */
@Component
@ConditionalOnMissingBean(name = "smtpMailTransport")
class LoggingMailTransport implements MailTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailTransport.class);

    LoggingMailTransport() {
        log.warn("Nenhum transporte SMTP configurado: os e-mails são processados pela outbox e registados em log");
    }

    @Override
    public void deliver(String to, String subject, String textBody) {
        log.info("E-mail para {}: {}", to, subject);
        log.debug("Corpo do e-mail para {}:\n{}", to, textBody);
    }
}
