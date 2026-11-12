package pt.voleibol.gestao.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Critério de conclusão da fase 0: o tenant A não lê dados do tenant B.
 * Corre contra um PostgreSQL real, com o papel da aplicação (volei_app), sem passar pelo Spring,
 * para testar exatamente a defesa da base de dados.
 */
@Testcontainers
class TenantIsolationTest {

    private static final String RLS_VIOLATION = "42501";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID CLUBE_A = UUID.randomUUID();
    static final UUID CLUBE_B = UUID.randomUUID();
    static UUID atletaB;

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    @BeforeAll
    static void prepararBaseDeDados() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "volei_migrator", "dev")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection c = connectAs("volei_migrator")) {
            insertTenant(c, CLUBE_A, "Clube A", "clube-a");
            insertTenant(c, CLUBE_B, "Clube B", "clube-b");
        }

        inTenant(CLUBE_A, c -> insertAthlete(c, CLUBE_A, "Ana (A)"));
        inTenant(CLUBE_A, c -> insertAthlete(c, CLUBE_A, "Beatriz (A)"));
        atletaB = inTenant(CLUBE_B, c -> insertAthlete(c, CLUBE_B, "Carla (B)"));
    }

    @Test
    void tenantVeApenasOsSeusDados() throws SQLException {
        java.util.List<String> nomesA = inTenant(CLUBE_A, c -> names(c));
        java.util.List<String> nomesB = inTenant(CLUBE_B, c -> names(c));
        assertThat(nomesA).containsExactlyInAnyOrder("Ana (A)", "Beatriz (A)");
        assertThat(nomesB).containsExactly("Carla (B)");
    }

    @Test
    void semTenantNaoSeVeNada() throws SQLException {
        try (Connection c = connectAs("volei_app")) {
            c.setAutoCommit(false);
            assertThat(names(c)).isEmpty();
        }
    }

    @Test
    void naoSePodeInserirLinhaDeOutroTenant() {
        assertThatThrownBy(() -> inTenant(CLUBE_A, c -> insertAthlete(c, CLUBE_B, "Intrusa")))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(RLS_VIOLATION));
    }

    @Test
    void naoSePodeAlterarNemApagarLinhaDeOutroTenant() throws SQLException {
        int alteradas = inTenant(CLUBE_A, c -> executeUpdate(c,
                "UPDATE athlete SET full_name = 'Hackeada' WHERE id = ?", atletaB));
        int apagadas = inTenant(CLUBE_A, c -> executeUpdate(c,
                "DELETE FROM athlete WHERE id = ?", atletaB));

        assertThat(alteradas).isZero();
        assertThat(apagadas).isZero();
        java.util.List<String> nomesB = inTenant(CLUBE_B, c -> names(c));
        assertThat(nomesB).containsExactly("Carla (B)");
    }

    @Test
    void tenantNaoSobreviveAoFimDaTransacaoNaMesmaLigacao() throws SQLException {
        // Simula o pool: a mesma ligação serve dois pedidos seguidos.
        try (Connection c = connectAs("volei_app")) {
            c.setAutoCommit(false);
            setTenant(c, CLUBE_A);
            assertThat(names(c)).isNotEmpty();
            c.commit();

            assertThat(names(c)).isEmpty();
            c.commit();
        }
    }

    @Test
    void auditoriaSoAceitaInsercoes() throws SQLException {
        inTenant(CLUBE_A, c -> executeUpdate(c,
                "INSERT INTO audit_log (tenant_id, action, entity) VALUES (?, 'CREATE', 'athlete')",
                CLUBE_A));

        assertThatThrownBy(() -> inTenant(CLUBE_A, c -> executeUpdate(c,
                "UPDATE audit_log SET action = 'ALTERADO'")))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(RLS_VIOLATION));
        assertThatThrownBy(() -> inTenant(CLUBE_A, c -> executeUpdate(c, "DELETE FROM audit_log")))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(RLS_VIOLATION));
    }

    @Test
    void papelDaAplicacaoNaoContornaRls() throws SQLException {
        try (Connection c = connectAs("volei_app");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("rolsuper")).isFalse();
            assertThat(rs.getBoolean("rolbypassrls")).isFalse();
        }

        try (Connection c = connectAs("volei_app");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).as("tabelas de que a aplicação é dona").isZero();
        }
    }

    @Test
    void todasAsTabelasDeNegocioTemRlsForcada() throws SQLException {
        try (Connection c = connectAs("volei_migrator");
             PreparedStatement ps = c.prepareStatement("""
                     SELECT relname FROM pg_class cl
                     JOIN pg_namespace ns ON ns.oid = cl.relnamespace
                     WHERE ns.nspname = 'public' AND cl.relkind = 'r'
                       AND EXISTS (SELECT 1 FROM pg_attribute a
                                   WHERE a.attrelid = cl.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped)
                       AND NOT (cl.relrowsecurity AND cl.relforcerowsecurity)
                     """);
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("tabela com tenant_id sem RLS forçada").isFalse();
        }

        try (Connection c = connectAs("volei_migrator");
             PreparedStatement ps = c.prepareStatement("""
                     SELECT relrowsecurity, relforcerowsecurity
                     FROM pg_class cl
                     JOIN pg_namespace ns ON ns.oid = cl.relnamespace
                     WHERE ns.nspname = 'public' AND cl.relkind = 'r' AND cl.relname = 'app_user'
                     """);
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("relrowsecurity")).as("app_user com RLS ativa").isTrue();
            assertThat(rs.getBoolean("relforcerowsecurity")).as("app_user com RLS forçada").isTrue();
        }
    }

    @Test
    void appUserSoEVisivelComRelacaoAoTenant() throws SQLException {
        UUID utilizadorB = UUID.randomUUID();
        try (Connection c = connectAs("volei_migrator")) {
            insertUser(c, utilizadorB, "so-b@test.local", "Só B");
            insertMembership(c, CLUBE_B, utilizadorB, "TREINADOR");
        }

        UUID invisivelEmA = inTenant(CLUBE_A, c -> findUserIdByEmail(c, "so-b@test.local"));
        assertThat(invisivelEmA).as("utilizador de outro clube visível").isNull();

        int alterados = inTenant(CLUBE_A, c -> executeUpdate(c,
                "UPDATE app_user SET name = 'Hackeado' WHERE id = ?", utilizadorB));
        assertThat(alterados).isZero();

        UUID visivelEmB = inTenant(CLUBE_B, c -> findUserIdByEmail(c, "so-b@test.local"));
        assertThat(visivelEmB).isEqualTo(utilizadorB);

        try (Connection c = connectAs("volei_app")) {
            c.setAutoCommit(false);
            UUID semTenant = findUserIdByEmail(c, "so-b@test.local");
            assertThat(semTenant).as("app_user visível sem tenant").isNull();
            c.commit();
        }
    }

    // --- auxiliares ---

    private static Connection connectAs(String user) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, "dev");
    }

    private static <T> T inTenant(UUID tenant, Work<T> work) throws SQLException {
        try (Connection c = connectAs("volei_app")) {
            c.setAutoCommit(false);
            setTenant(c, tenant);
            T result = work.run(c);
            c.commit();
            return result;
        }
    }

    private static void setTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }

    private static void insertTenant(Connection c, UUID id, String name, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenant (id, name, slug) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, slug);
            ps.executeUpdate();
        }
    }

    private static UUID insertAthlete(Connection c, UUID tenant, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO athlete (id, tenant_id, full_name, birth_date, gender) "
                        + "VALUES (?, ?, ?, DATE '2010-05-01', 'F')")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, name);
            ps.executeUpdate();
        }
        return id;
    }

    private static java.util.List<String> names(Connection c) throws SQLException {
        java.util.List<String> result = new java.util.ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT full_name FROM athlete");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    private static void insertUser(Connection c, UUID id, String email, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO app_user (id, email, name) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, email);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private static void insertMembership(Connection c, UUID tenant, UUID user, String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO membership (tenant_id, user_id, role) VALUES (?, ?, ?)")) {
            ps.setObject(1, tenant);
            ps.setObject(2, user);
            ps.setString(3, role);
            ps.executeUpdate();
        }
    }

    private static UUID findUserIdByEmail(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM app_user WHERE lower(email) = lower(?)")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    private static int executeUpdate(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }
}
