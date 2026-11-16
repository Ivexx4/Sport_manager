package pt.voleibol.gestao.shared;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Registo de auditoria ({@code audit_log}), injetado nos serviços. Os repositórios deixaram de expor
 * um método {@code audit} próprio: a escrita é sempre a mesma e não depende de SQL de cada módulo.
 */
@Component
public class AuditLog {

    private final JdbcClient jdbc;

    AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Operação sobre uma entidade do módulo ({@code athlete}, {@code charge}, {@code team}, ...). */
    public void record(UUID tenantId, UUID actorId, String action, String entity, UUID entityId) {
        Db.audit(jdbc, tenantId, actorId, action, entity, entityId);
    }

    /**
     * Operação de sessão/conta (login, MFA, passwords, membros): fica com a entidade {@code session}
     * e o identificador do próprio utilizador, como sempre ficou.
     */
    public void record(UUID tenantId, UUID userId, String action) {
        Db.audit(jdbc, tenantId, userId, action, "session", userId);
    }
}
