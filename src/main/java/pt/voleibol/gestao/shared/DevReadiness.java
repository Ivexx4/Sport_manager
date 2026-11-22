package pt.voleibol.gestao.shared;

import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Prontidão DEV: não confundir um processo vivo com uma base de dados migrada. */
@Component
@Profile("dev")
class DevReadiness implements ApplicationRunner {

    private final Flyway flyway;
    private final JdbcClient jdbc;
    private volatile boolean ready;

    DevReadiness(Flyway flyway, JdbcClient jdbc) {
        this.flyway = flyway;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        validateDatabase();
        ready = true;
    }

    /** Revalidar em cada ping deteta uma BD recriada após o arranque e a reconexão do pool. */
    void requireReady() {
        if (!ready) {
            throw unavailable();
        }
        try {
            validateDatabase();
        } catch (RuntimeException ex) {
            // O endpoint público não expõe URLs, credenciais nem detalhes internos de SQL.
            throw unavailable();
        }
    }

    private void validateDatabase() {
        var info = flyway.info();
        if (info.all().length == 0 || info.pending().length != 0) {
            throw new IllegalStateException("A BD DEV tem migrações por aplicar. Reinicia o backend antes do seed.");
        }
        flyway.validate();
        // Verificar também a ligação da aplicação, não apenas a ligação privilegiada do Flyway.
        boolean tablesPresent = jdbc.sql("""
                SELECT to_regclass('public.tenant') IS NOT NULL
                   AND to_regclass('public.app_user') IS NOT NULL
                   AND to_regclass('public.membership') IS NOT NULL
                """).query(Boolean.class).single();
        if (!tablesPresent) {
            throw new IllegalStateException("A ligação da aplicação aponta para uma BD sem o esquema esperado.");
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "A BD DEV não está pronta. Reinicia o backend para validar e aplicar as migrações antes do seed.");
    }
}
