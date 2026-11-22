package pt.voleibol.gestao.shared;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Esquema e histórico reais; nunca alterar a BD de desenvolvimento para simular uma recriação. */
class DevReadinessTest {

    private static final TestDatabase.Base BASE = TestDatabase.base(DevReadinessTest.class);
    private Flyway flyway;
    private DevReadiness readiness;

    @BeforeEach
    void prepararBaseIsolada() throws SQLException {
        recriarEsquema();
        flyway = flyway(null);
        readiness = new DevReadiness(flyway, jdbc(BASE));
    }

    @Test
    void naoConfundeArranqueEmCursoComProntidao() {
        flyway.migrate();
        assertUnavailable(readiness);
    }

    @Test
    void bdVaziaImpedeProntidaoAntesDoSeed() {
        assertThatThrownBy(() -> readiness.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("migrações por aplicar");
        assertUnavailable(readiness);
    }

    @Test
    void migracoesConcluidasPermitemPingDevComIdentidadeDoProcesso() {
        flyway.migrate();
        readiness.run(new DefaultApplicationArguments());
        assertThat(new PingController(Optional.of(readiness)).ping())
                .containsEntry("status", "ok")
                .containsEntry("database", "ready")
                .containsEntry("processId", ProcessHandle.current().pid());
    }

    @Test
    void migracaoPendenteImpedeProntidao() {
        flyway("24").migrate();
        assertThatThrownBy(() -> readiness.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("migrações por aplicar");
        assertUnavailable(readiness);
    }

    @Test
    void historicoIncompativelImpedeProntidao() throws SQLException {
        flyway.migrate();
        sql("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
        assertThatThrownBy(() -> readiness.run(new DefaultApplicationArguments()))
                .hasMessageContaining("checksum mismatch");
        assertUnavailable(readiness);
    }

    @Test
    void pingDetetaRecriacaoDaBdDepoisDoArranque() throws SQLException {
        flyway.migrate();
        readiness.run(new DefaultApplicationArguments());
        assertThatNoException().isThrownBy(readiness::requireReady);
        recriarEsquema();
        assertUnavailable(readiness);
        assertThatThrownBy(() -> new PingController(Optional.of(readiness)).ping())
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void tabelaAusenteNaoFicaOcultaPorHistoricoValido() throws SQLException {
        flyway.migrate();
        readiness.run(new DefaultApplicationArguments());
        sql("ALTER TABLE tenant RENAME TO tenant_antigo");
        assertUnavailable(readiness);
    }

    @Test
    void verificaTambemALigacaoDaAplicacao() {
        flyway.migrate();
        TestDatabase.Base outra = TestDatabase.base(AplicacaoNoutraBase.class);
        DevReadiness divergente = new DevReadiness(flyway, jdbc(outra));
        assertThatThrownBy(() -> divergente.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sem o esquema esperado");
    }

    @Test
    void protecaoNaoEAtivadaForaDeDev() {
        new ApplicationContextRunner().withUserConfiguration(DevReadiness.class)
                .withPropertyValues("spring.profiles.active=staging")
                .run(context -> assertThat(context).doesNotHaveBean(DevReadiness.class));
        assertThat(new PingController(Optional.empty()).ping())
                .containsEntry("status", "ok").doesNotContainKeys("database", "processId");
    }

    private static void assertUnavailable(DevReadiness readiness) {
        assertThatThrownBy(readiness::requireReady)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static JdbcClient jdbc(TestDatabase.Base base) {
        return JdbcClient.create(new DriverManagerDataSource(base.jdbcUrl(), "volei_app", TestDatabase.PAPEIS_PASSWORD));
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(BASE.jdbcUrl(), "volei_migrator", TestDatabase.PAPEIS_PASSWORD);
        if (target != null) { config.target(target); }
        return config.load();
    }

    private static void recriarEsquema() throws SQLException {
        sql("DROP SCHEMA public CASCADE; CREATE SCHEMA public AUTHORIZATION volei_migrator; GRANT USAGE ON SCHEMA public TO volei_app");
    }

    private static void sql(String sql) throws SQLException {
        try (Connection connection = BASE.superuser(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static class AplicacaoNoutraBase { }
}
