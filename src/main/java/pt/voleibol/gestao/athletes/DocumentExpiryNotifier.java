package pt.voleibol.gestao.athletes;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pt.voleibol.gestao.notifications.EmailSender;
import pt.voleibol.gestao.tenancy.TenantContext;

@Component
class DocumentExpiryNotifier {

    private static final Logger log = LoggerFactory.getLogger(DocumentExpiryNotifier.class);
    private static final int DAYS_AHEAD = 30;

    private final JdbcClient jdbc;
    private final DocumentRepository documents;
    private final EmailSender emailSender;
    private final TransactionTemplate transactions;

    DocumentExpiryNotifier(JdbcClient jdbc, DocumentRepository documents, EmailSender emailSender,
                           PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.emailSender = emailSender;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void notifyExpiring() {
        List<UUID> tenants = jdbc.sql("SELECT id FROM tenant").query(UUID.class).list();
        for (UUID tenantId : tenants) {
            try {
                // Cada clube corre na sua própria transação: o gestor de transações passa o tenant
                // à base de dados e as políticas de RLS deixam ver apenas os documentos desse clube.
                TenantContext.callAs(tenantId, () -> transactions.execute(status -> {
                    for (DocumentRepository.ExpiringRow row : documents.listExpiring(tenantId, DAYS_AHEAD)) {
                        String recipient = row.athleteEmail();
                        if (recipient == null || recipient.isBlank()) continue;
                        String subject = "Documento a expirar: " + row.type();
                        String body = "O documento '" + row.type() + "' do atleta " + row.athleteName()
                                + " expira em " + row.validUntil() + ".";
                        try {
                            emailSender.send(recipient, subject, body);
                            documents.markExpiryNotified(tenantId, row.documentId());
                        } catch (Exception e) {
                            log.warn("Falha ao notificar expiração do documento {}", row.documentId(), e);
                        }
                    }
                    return null;
                }));
            } catch (Exception e) {
                log.warn("Falha ao processar notificações de documentos para o clube {}", tenantId, e);
            }
        }
    }
}
