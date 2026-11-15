package pt.voleibol.gestao.shared;

import com.jayway.jsonpath.JsonPath;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import pt.voleibol.gestao.tenancy.TenantFilter;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Infraestrutura comum aos testes de ponta a ponta: PostgreSQL real, MockMvc com os filtros da
 * aplicação e os auxiliares de pedido e de semeadura usados por todos os domínios.
 */
@SpringBootTest(properties = "app.security.login-rate-limit.enabled=false")
@Testcontainers
public abstract class FlowTestSupport {

    @Container
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    protected static final String PASS = "palavra-passe-teste";

    /**
     * Propriedades da base de dados de teste. Cada classe de teste declara o seu próprio método
     * {@code @DynamicPropertySource} que delega aqui: assim o contexto Spring (e o contentor) não
     * são partilhados entre classes.
     */
    protected static void registarPropriedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "volei_app");
        registry.add("spring.datasource.password", () -> "dev");
        registry.add("spring.flyway.user", () -> "volei_migrator");
        registry.add("spring.flyway.password", () -> "dev");
        registry.add("app.security.jwt.secret", () -> Base64.getEncoder().encodeToString(
                "clave-de-teste-com-mais-de-32-bytes-ok!".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired
    protected WebApplicationContext context;
    @Autowired
    protected TenantFilter tenantFilter;
    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected MockMvc mvc;

    /** Hash da palavra-passe comum, calculado uma vez por classe no arranque dos testes. */
    protected static String passwordHash;

    @BeforeEach
    void prepararMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(tenantFilter)
                .apply(springSecurity())
                .build();
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASS);
        }
    }

    // --- pedidos ---

    protected String acesso(String host, String email) throws Exception {
        return acesso(host, email, PASS);
    }

    protected String acesso(String host, String email, String password) throws Exception {
        String resposta = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                        .with(host(host)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(resposta, "$.accessToken");
    }

    protected static RequestPostProcessor host(String serverName) {
        return request -> {
            request.setServerName(serverName);
            return request;
        };
    }

    protected static String json(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    protected static List<UUID> ids(String json) {
        List<String> valores = JsonPath.read(json, "$[*].id");
        return valores.stream().map(UUID::fromString).toList();
    }

    protected static UUID id(String email) {
        return UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8));
    }

    // --- base de dados ---

    protected static Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    protected static void executar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    protected static int contar(String sql, Object... params) throws SQLException {
        try (Connection c = superuser(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    protected static int contarAuditoria(String action) throws SQLException {
        return contar("SELECT count(*) FROM audit_log WHERE action = ?", action);
    }

    /** Id de um escalão do catálogo federal (V21), que já é semeado pela migração. */
    protected static UUID escalao(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM age_group WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Escalão inexistente no catálogo: " + code);
                }
                return rs.getObject(1, UUID.class);
            }
        }
    }

    protected static void clube(Connection c, UUID tenantId, String nome, String slug) throws SQLException {
        executar(c, """
                INSERT INTO tenant (id, name, slug) VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, slug = EXCLUDED.slug
                """, tenantId, nome, slug);
    }

    protected static void plantel(Connection c, UUID tenantId, UUID teamId, UUID athleteId) throws SQLException {
        executar(c, """
                INSERT INTO team_athlete (tenant_id, team_id, athlete_id) VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, team_id, athlete_id) DO NOTHING
                """, tenantId, teamId, athleteId);
    }

    /** Atleta semeado; email e conta ligada são opcionais e ficam nulos quando não são indicados. */
    protected static void atleta(Connection c, UUID athleteId, UUID tenantId, String nome, String nascimento,
                                 String genero, String licenca, UUID userId, String email) throws SQLException {
        executar(c, """
                INSERT INTO athlete (id, tenant_id, full_name, birth_date, gender, nif, email, phone,
                                     license_number, status, user_id)
                VALUES (?, ?, ?, ?, ?, NULL, ?, NULL, ?, 'ACTIVE', ?)
                ON CONFLICT (id) DO UPDATE SET full_name = EXCLUDED.full_name,
                    birth_date = EXCLUDED.birth_date, gender = EXCLUDED.gender,
                    nif = NULL, email = EXCLUDED.email, phone = NULL,
                    license_number = EXCLUDED.license_number, status = 'ACTIVE',
                    user_id = EXCLUDED.user_id, anonymized_at = NULL
                """, athleteId, tenantId, nome, Date.valueOf(nascimento), genero, email, licenca, userId);
    }

    protected static void atleta(Connection c, UUID athleteId, UUID tenantId, String nome, String nascimento,
                                 String genero, String licenca, UUID userId) throws SQLException {
        atleta(c, athleteId, tenantId, nome, nascimento, genero, licenca, userId, null);
    }

    /** Conta de teste com a palavra-passe comum e os papéis indicados no clube. */
    protected static void membro(Connection c, String email, UUID tenantId, String... roles) throws SQLException {
        executar(c, """
                INSERT INTO app_user (id, email, name, password_hash) VALUES (?, ?, 'Teste', ?)
                ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, name = EXCLUDED.name,
                    password_hash = EXCLUDED.password_hash, status = 'ACTIVE'
                """, id(email), email, passwordHash);
        for (String role : roles) {
            executar(c, """
                    INSERT INTO membership (tenant_id, user_id, role) VALUES (?, ?, ?)
                    ON CONFLICT (tenant_id, user_id, role) DO UPDATE SET status = 'ACTIVE'
                    """, tenantId, id(email), role);
        }
    }

    protected static void equipa(Connection c, UUID teamId, UUID tenantId, String nome) throws SQLException {
        executar(c, """
                INSERT INTO team (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE', anonymized_at = NULL
                """, teamId, tenantId, nome);
    }

    protected static void equipa(Connection c, UUID teamId, UUID tenantId, String nome, UUID seasonId,
                                 UUID ageGroupId) throws SQLException {
        executar(c, """
                INSERT INTO team (id, tenant_id, name, status, season_id, age_group_id)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE',
                    season_id = EXCLUDED.season_id, age_group_id = EXCLUDED.age_group_id,
                    anonymized_at = NULL
                """, teamId, tenantId, nome, seasonId, ageGroupId);
    }

    protected static void epoca(Connection c, UUID seasonId, UUID tenantId, String nome) throws SQLException {
        executar(c, """
                INSERT INTO season (id, tenant_id, name, start_date, end_date, is_active)
                VALUES (?, ?, ?, ?, ?, true)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, start_date = EXCLUDED.start_date,
                    end_date = EXCLUDED.end_date, is_active = true, anonymized_at = NULL
                """, seasonId, tenantId, nome, Date.valueOf("2025-08-01"), Date.valueOf("2026-07-31"));
    }

    /** Época com data de corte, usada nos testes de escalões por ano de nascimento. */
    protected static void epocaComCorte(Connection c, UUID seasonId, UUID tenantId, String nome)
            throws SQLException {
        executar(c, """
                INSERT INTO season (id, tenant_id, name, start_date, end_date, cutoff_date, is_active)
                VALUES (?, ?, ?, ?, ?, ?, true)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, start_date = EXCLUDED.start_date,
                    end_date = EXCLUDED.end_date, cutoff_date = EXCLUDED.cutoff_date, is_active = true,
                    anonymized_at = NULL
                """, seasonId, tenantId, nome, Date.valueOf("2025-08-01"), Date.valueOf("2026-07-31"),
                Date.valueOf("2026-01-01"));
    }

    protected static void local(Connection c, UUID venueId, UUID tenantId, String nome) throws SQLException {
        executar(c, """
                INSERT INTO venue (id, tenant_id, name) VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, anonymized_at = NULL
                """, venueId, tenantId, nome);
    }

    /** Apaga os dados das tabelas indicadas para os dois clubes envolvidos na maioria dos testes. */
    protected static void limpar(Connection c, UUID tenantA, UUID tenantB, String... tabelas)
            throws SQLException {
        for (String tabela : tabelas) {
            executar(c, "DELETE FROM " + tabela + " WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        }
    }
}
