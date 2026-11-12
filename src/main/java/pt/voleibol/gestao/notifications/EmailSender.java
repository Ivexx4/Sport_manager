package pt.voleibol.gestao.notifications;

/**
 * Interface pública do módulo notifications para enviar e-mail em texto simples.
 * A implementação real (SMTP ou fornecedor, de preferência através de uma tabela outbox) substitui
 * a que regista em log: basta definir outro bean {@code @Primary} que implemente esta interface.
 */
public interface EmailSender {

    void send(String to, String subject, String textBody);
}
