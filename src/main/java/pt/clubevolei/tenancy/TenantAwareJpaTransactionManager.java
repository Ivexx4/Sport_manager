package pt.clubevolei.tenancy;

import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * No início de cada transação define app.tenant_id na sessão PostgreSQL, com âmbito de transação
 * (set_config(..., true) equivale a SET LOCAL). Ao terminar a transação o valor desaparece, por isso
 * uma ligação devolvida ao pool nunca leva o tenant de um pedido para o seguinte.
 *
 * Sem tenant no contexto nada é definido e as políticas RLS não devolvem linha nenhuma.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        UUID tenantId = TenantContext.current().orElse(null);
        if (tenantId != null) {
            // Usa a ligação já ligada a esta transação (via DataSourceUtils).
            new JdbcTemplate(getDataSource())
                    .queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        }
    }
}
