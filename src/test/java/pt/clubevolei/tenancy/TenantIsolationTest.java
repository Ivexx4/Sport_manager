package pt.clubevolei.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import pt.clubevolei.athlete.Athlete;
import pt.clubevolei.athlete.AthleteRepository;

/**
 * Prova, contra um PostgreSQL real, que a RLS isola os clubes. É o teste mais importante do projeto:
 * tem de correr (e passar) em todos os builds.
 *
 * A migração corre com o utilizador da imagem (superuser); a aplicação liga-se como clube_app.
 */
@SpringBootTest
@Testcontainers
class TenantIsolationTest {

    static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/initdb/01-roles.sql"),
                    "/docker-entrypoint-initdb.d/01-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "clube_app");
        registry.add("spring.datasource.password", () -> "clube_app");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    AthleteRepository athletes;
    @Autowired
    JdbcTemplate appJdbc; // liga como clube_app (sujeito a RLS)

    TransactionTemplate tx;
    JdbcTemplate ownerJdbc; // superuser: só para preparar dados

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        ownerJdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        ownerJdbc.update("DELETE FROM athlete");
        ownerJdbc.update("DELETE FROM tenant");
        ownerJdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, 'clube-a', 'Clube A')", TENANT_A);
        ownerJdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, 'clube-b', 'Clube B')", TENANT_B);
    }

    private void saveAs(UUID tenant, String name) {
        TenantContext.runAs(tenant, () -> tx.executeWithoutResult(
                status -> athletes.save(new Athlete(name, LocalDate.of(2010, 5, 17)))));
    }

    private List<String> namesSeenBy(UUID tenant) {
        return TenantContext.callAs(tenant, () -> tx.execute(
                status -> athletes.findAll().stream().map(Athlete::getFullName).sorted().toList()));
    }

    @Test
    void aplicacaoNaoTemPrivilegiosQueContornemRls() {
        Boolean privileged = tx.execute(status -> appJdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class));
        assertThat(privileged).isFalse();
    }

    @Test
    void cadaTenantVeApenasOsSeusAtletas() {
        saveAs(TENANT_A, "Ana (A)");
        saveAs(TENANT_A, "Rui (A)");
        saveAs(TENANT_B, "Bia (B)");

        assertThat(namesSeenBy(TENANT_A)).containsExactly("Ana (A)", "Rui (A)");
        assertThat(namesSeenBy(TENANT_B)).containsExactly("Bia (B)");
    }

    @Test
    void semTenantNaoSeVeNada() {
        saveAs(TENANT_A, "Ana (A)");

        TenantContext.clear();
        Integer count = tx.execute(status -> appJdbc.queryForObject("SELECT count(*) FROM athlete", Integer.class));

        assertThat(count).isZero();
    }

    @Test
    void naoEPossivelInserirNoutroTenant() {
        // Mesmo contornando o JPA com SQL nativo, o WITH CHECK da política recusa a linha.
        assertThatThrownBy(() -> TenantContext.runAs(TENANT_A, () -> tx.executeWithoutResult(
                status -> appJdbc.update(
                        "INSERT INTO athlete (tenant_id, full_name) VALUES (?, 'Intruso')", TENANT_B))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void naoEPossivelAlterarNemApagarAtletasDeOutroTenant() {
        saveAs(TENANT_A, "Ana (A)");

        Integer[] affected = TenantContext.callAs(TENANT_B, () -> tx.execute(status -> new Integer[] {
                appJdbc.update("UPDATE athlete SET full_name = 'Hackeada'"),
                appJdbc.update("DELETE FROM athlete")
        }));

        assertThat(affected).containsExactly(0, 0);
        assertThat(namesSeenBy(TENANT_A)).containsExactly("Ana (A)");
    }

    @Test
    void tenantNaoVazaEntreTransacoesNoPool() {
        saveAs(TENANT_A, "Ana (A)");

        // A transação seguinte, sem tenant, reutiliza (provavelmente) a mesma ligação do pool.
        TenantContext.clear();
        String leaked = tx.execute(status -> appJdbc.queryForObject(
                "SELECT nullif(current_setting('app.tenant_id', true), '')", String.class));

        assertThat(leaked).isNull();
    }
}
