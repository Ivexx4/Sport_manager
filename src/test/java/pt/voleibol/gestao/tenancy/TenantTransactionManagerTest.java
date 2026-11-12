package pt.voleibol.gestao.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica a ligação entre o Spring e a RLS: o gestor de transações passa o tenant à base de dados
 * e o valor não fica na ligação quando esta volta ao pool (pool de tamanho 1 para forçar a reutilização).
 */
@SpringBootTest
@Testcontainers
class TenantTransactionManagerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.randomUUID();
    static final UUID CLUBE_B = UUID.randomUUID();

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "volei_app");
        registry.add("spring.datasource.password", () -> "dev");
        registry.add("spring.flyway.user", () -> "volei_migrator");
        registry.add("spring.flyway.password", () -> "dev");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("app.security.jwt.secret", () -> java.util.Base64.getEncoder().encodeToString(
                "clave-de-teste-com-mais-de-32-bytes-ok!".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** Os tenants são dados da plataforma: a aplicação não os pode escrever, por isso usa-se o papel dono. */
    private void garantirTenants() throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "volei_migrator", "dev");
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO tenant (id, name, slug) VALUES (?, ?, ?), (?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setObject(1, CLUBE_A);
            ps.setString(2, "Clube A");
            ps.setString(3, "clube-a");
            ps.setObject(4, CLUBE_B);
            ps.setString(5, "Clube B");
            ps.setString(6, "clube-b");
            ps.executeUpdate();
        }
    }

    private <T> T emTransacao(UUID tenant, java.util.function.Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        if (tenant == null) {
            return template.execute(status -> work.get());
        }
        return TenantContext.callAs(tenant, () -> template.execute(status -> work.get()));
    }

    @Test
    void transacaoRecebeOTenantDoContexto() {
        String tenant = emTransacao(CLUBE_A,
                () -> jdbc.queryForObject("SELECT app_current_tenant()::text", String.class));

        assertThat(tenant).isEqualTo(CLUBE_A.toString());
    }

    @Test
    void tenantNaoVazaParaOPedidoSeguinte() {
        emTransacao(CLUBE_A, () -> jdbc.queryForObject("SELECT 1", Integer.class));

        // pool de tamanho 1: é a mesma ligação, agora sem tenant no contexto
        String tenant = emTransacao(null,
                () -> jdbc.queryForObject("SELECT app_current_tenant()::text", String.class));

        assertThat(tenant).isNull();
    }

    @Test
    void jdbcTemplateDentroDeTransacaoRespeitaRls() throws SQLException {
        garantirTenants();

        emTransacao(CLUBE_A, () -> jdbc.update(
                "INSERT INTO athlete (tenant_id, full_name, birth_date, gender) "
                        + "VALUES (?, 'Atleta do A', DATE '2011-03-02', 'M')", CLUBE_A));
        emTransacao(CLUBE_B, () -> jdbc.update(
                "INSERT INTO athlete (tenant_id, full_name, birth_date, gender) "
                        + "VALUES (?, 'Atleta do B', DATE '2011-03-02', 'M')", CLUBE_B));

        List<String> vistosPeloA = emTransacao(CLUBE_A,
                () -> jdbc.queryForList("SELECT full_name FROM athlete", String.class));
        List<String> vistosSemTenant = emTransacao(null,
                () -> jdbc.queryForList("SELECT full_name FROM athlete", String.class));

        assertThat(vistosPeloA).containsExactly("Atleta do A");
        assertThat(vistosSemTenant).isEmpty();
    }
}
