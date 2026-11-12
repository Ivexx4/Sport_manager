package pt.voleibol.gestao.notifications;

/** Transporte efetivo de e-mail (SMTP, log, etc.), usado pelo processador da outbox. */
public interface MailTransport {

    void deliver(String to, String subject, String textBody);
}
