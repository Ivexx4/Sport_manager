package pt.voleibol.gestao.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Falha ao definir o tenant na ligação: a transação é limpa e o erro é traduzido pelo Spring. */
class TenantAwareJpaTransactionManagerTest {

    private static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    void falhaAoDefinirOTenantFazRollbackELancaCannotCreateTransaction() throws Exception {
        EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);
        EntityManager entityManager = mock(EntityManager.class);
        EntityTransaction entityTransaction = mock(EntityTransaction.class);
        when(entityManagerFactory.createEntityManager()).thenReturn(entityManager);
        when(entityManager.getTransaction()).thenReturn(entityTransaction);

        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("sem ligação"));

        TenantAwareJpaTransactionManager manager =
                new TenantAwareJpaTransactionManager(entityManagerFactory);
        manager.setDataSource(dataSource);
        manager.afterPropertiesSet();

        TransactionTemplate template = new TransactionTemplate(manager);
        TenantContext.set(CLUBE_A);

        assertThatThrownBy(() -> template.execute(status -> "trabalho"))
                .isInstanceOf(CannotCreateTransactionException.class)
                .hasMessageContaining("tenant");

        verify(connection).close();
    }
}
