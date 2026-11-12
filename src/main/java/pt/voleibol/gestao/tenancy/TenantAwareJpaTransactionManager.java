package pt.voleibol.gestao.tenancy;

import jakarta.persistence.EntityManagerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

/**
 * No início de cada transação, passa o tenant atual à base de dados com
 * {@code set_config('app.tenant_id', ..., true)}. O {@code true} limita o valor à transação,
 * o que é obrigatório com pool de ligações: quando a transação acaba, o valor desaparece e a
 * ligação volta ao pool sem tenant.
 *
 * <p>Sem tenant no contexto, define-se o valor vazio: as políticas RLS não deixam ver nenhuma linha.
 * Serve tanto JPA como JdbcTemplate, que partilham a mesma ligação transacional.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public TenantAwareJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        DataSource dataSource = getDataSource();
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, TenantContext.current().map(UUID::toString).orElse(""));
            statement.execute();
        } catch (SQLException e) {
            // o Spring não limpa uma transação cujo doBegin falhou depois de começar
            doCleanupAfterCompletion(transaction);
            throw new CannotCreateTransactionException(
                    "Não foi possível definir o tenant da transação", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
