package pt.voleibol.gestao.shared;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contentor PostgreSQL da suite: um único por JVM, com uma base de dados por classe de teste.
 *
 * <p>Arrancar o contentor custa segundos e não depende do que está a ser testado, por isso é feito
 * uma só vez e desligado no fim da JVM. Cada classe pede a sua base com {@link #base(Class)}, que
 * nasce vazia e é migrada pelo Flyway ao subir o contexto: os dados de um teste não aparecem nas
 * asserções de outro, tal como quando cada classe arrancava o seu próprio contentor. O script
 * {@code db/init/01-roles.sql} continua a ser a única fonte de verdade dos papéis e privilégios: é
 * aplicado a cada base nova, tolerando os papéis que já existem.
 */
public final class TestDatabase {

    /** Palavra-passe dos papéis {@code volei_app} e {@code volei_migrator} definida no script. */
    public static final String PAPEIS_PASSWORD = "dev";

    private static final String ROLES_SCRIPT = "/db/init/01-roles.sql";
    private static final String DUPLICATE_OBJECT = "42710";
    private static final int POSTGRES_PORT = 5432;
    private static final int NOME_MAXIMO = 40;

    private static final PostgreSQLContainer POSTGRES = iniciar();

    private static final Map<String, Base> BASES = new ConcurrentHashMap<>();

    private TestDatabase() {
    }

    private static PostgreSQLContainer iniciar() {
        PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withInitScript("db/init/01-roles.sql");
        postgres.start();
        Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop, "testcontainers-postgres"));
        return postgres;
    }

    /**
     * Base de dados exclusiva da classe de teste indicada, criada na primeira chamada.
     *
     * @param testClass classe que vai usar a base; o nome é derivado do nome da classe
     */
    public static Base base(Class<?> testClass) {
        return BASES.computeIfAbsent(nomeDe(testClass), TestDatabase::criar);
    }

    /** {@code AthleteFlowTest} passa a {@code athlete_flow_test}: nome válido na base e legível. */
    private static String nomeDe(Class<?> testClass) {
        String nome = testClass.getSimpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
        return nome.length() <= NOME_MAXIMO ? nome : nome.substring(0, NOME_MAXIMO);
    }

    private static Base criar(String nome) {
        String url = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
                POSTGRES.getMappedPort(POSTGRES_PORT), nome);
        try {
            try (Connection admin = admin(POSTGRES.getJdbcUrl()); Statement st = admin.createStatement()) {
                st.execute("CREATE DATABASE " + nome);
            }
            try (Connection nova = admin(url); Statement st = nova.createStatement()) {
                for (String sql : scriptDePapeis()) {
                    try {
                        st.execute(sql);
                    } catch (SQLException e) {
                        if (!DUPLICATE_OBJECT.equals(e.getSQLState())) {
                            throw e;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível preparar a base de dados " + nome, e);
        }
        return new Base(url);
    }

    private static Connection admin(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Instruções do script de papéis, sem comentários e sem os separadores. */
    private static List<String> scriptDePapeis() {
        try (InputStream script = TestDatabase.class.getResourceAsStream(ROLES_SCRIPT)) {
            if (script == null) {
                throw new IllegalStateException("Script de papéis ausente do classpath: " + ROLES_SCRIPT);
            }
            String sql = new String(script.readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(sql.replaceAll("(?m)^\\s*--.*$", "").split(";"))
                    .map(String::trim)
                    .filter(instrucao -> !instrucao.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler " + ROLES_SCRIPT, e);
        }
    }

    /**
     * Base de dados de teste e as propriedades que o Spring precisa para lá chegar.
     *
     * @param jdbcUrl URL usada pela aplicação (papel {@code volei_app})
     */
    public record Base(String jdbcUrl) {

        /** Ligações à base de dados: o Spring (aplicação) e o Flyway (dono do esquema). */
        public void registar(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", this::jdbcUrl);
            registry.add("spring.datasource.username", () -> "volei_app");
            registry.add("spring.datasource.password", () -> PAPEIS_PASSWORD);
            // A suite guarda vários contextos Spring vivos em simultâneo no mesmo contentor: um pool
            // pequeno chega a testes sequenciais e evita esgotar as ligações do PostgreSQL (100).
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
            registry.add("spring.flyway.user", () -> "volei_migrator");
            registry.add("spring.flyway.password", () -> PAPEIS_PASSWORD);
            registry.add("app.security.jwt.secret", TestDatabase::segredoJwt);
        }

        /** Ligação com o superutilizador do PostgreSQL: ignora RLS e escreve dados da plataforma. */
        public Connection superuser() throws SQLException {
            return admin(jdbcUrl);
        }

        /** Ligação com um dos papéis do script ({@code volei_app} ou {@code volei_migrator}). */
        public Connection papel(String utilizador) throws SQLException {
            return DriverManager.getConnection(jdbcUrl, utilizador, PAPEIS_PASSWORD);
        }
    }

    /** Segredo HS256 de teste: base64 de uma frase com mais de 32 bytes. */
    private static String segredoJwt() {
        return Base64.getEncoder().encodeToString(
                "clave-de-teste-com-mais-de-32-bytes-ok!".getBytes(StandardCharsets.UTF_8));
    }
}
