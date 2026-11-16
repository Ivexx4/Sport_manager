package pt.voleibol.gestao.tenancy;

import java.sql.Connection;
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

import pt.voleibol.gestao.shared.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica a ligação entre o Spring e a RLS: o gestor de transações passa o tenant à base de dados
 * e o valor não fica na ligação quando esta volta ao pool (pool de tamanho 1 para forçar a reutilização).
 */
@SpringBootTest
class TenantTransactionManagerTest {

    /** Base exclusiva: o pool de tamanho 1 exige uma base que mais nada esteja a usar. */
    static final TestDatabase.Base BASE = TestDatabase.base(TenantTransactionManagerTest.class);

    static final UUID CLUBE_A = UUID.randomUUID();
    static final UUID CLUBE_B = UUID.randomUUID();

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        BASE.registar(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** Os tenants são dados da plataforma: a aplicação não os pode escrever, por isso usa-se o papel dono. */
    private void garantirTenants() throws SQLException {
        try (Connection c = BASE.papel("volei_migrator");
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
