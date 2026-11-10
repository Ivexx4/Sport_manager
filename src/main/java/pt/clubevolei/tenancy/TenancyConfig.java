package pt.clubevolei.tenancy;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;

@Configuration(proxyBeanMethods = false)
public class TenancyConfig {

    /**
     * Bean com o nome padrão "transactionManager": a auto-configuração do Boot deixa de criar o seu.
     * O DataSource é definido explicitamente para que o JdbcTemplate use a ligação da transação JPA.
     */
    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory emf, DataSource dataSource) {
        TenantAwareJpaTransactionManager tm = new TenantAwareJpaTransactionManager(emf);
        tm.setDataSource(dataSource);
        return tm;
    }
}
