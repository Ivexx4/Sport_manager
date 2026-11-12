package pt.voleibol.gestao.maintenance;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Limpeza periódica contra PostgreSQL real.
 *
 * <p>Prova que só sai o que já não pode ser usado, que a limpeza atravessa o RLS de vários clubes
 * numa só chamada e que os dados recentes/válidos ficam intactos.
 */
@SpringBootTest(properties = {
        "app.maintenance.cleanup.enabled=true",
        "app.maintenance.cleanup.initial-delay=PT1H"
})
@Testcontainers
class ExpiredDataPurgerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/01-roles.sql");

    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000a101");
    static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000b101");
    static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-00000000a102");
    static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-00000000b102");

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "volei_app");
        registry.add("spring.datasource.password", () -> "dev");
        registry.add("spring.flyway.user", () -> "volei_migrator");
        registry.add("spring.flyway.password", () -> "dev");
        registry.add("app.security.jwt.secret", () -> Base64.getEncoder().encodeToString(
                "clave-de-teste-com-mais-de-32-bytes-ok!".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired
    ExpiredDataPurger purger;

    @BeforeEach
    void semear() throws SQLException {
        try (Connection c = superuser()) {
            clube(c, TENANT_A, "Clube Retenção A", "retencao-a");
            clube(c, TENANT_B, "Clube Retenção B", "retencao-b");
            utilizador(c, USER_A, "retencao-a@test.local");
            utilizador(c, USER_B, "retencao-b@test.local");
            dados(c);
        }
    }

    @Test
    void removeApenasDadosExpiradosDeTodosOsClubes() throws SQLException {
        ExpiredDataPurger.Purged purged = purger.purge();

        assertThat(purged).isEqualTo(new ExpiredDataPurger.Purged(4, 3, 3, 2));

        assertThat(valores("invitation", "token_hash"))
                .containsExactlyInAnyOrder("inv-a-expirado-recente", "inv-a-valido");
        assertThat(valores("password_reset_token", "token_hash"))
                .containsExactlyInAnyOrder("prt-a-expirado-recente", "prt-a-valido");
        assertThat(valores("refresh_token", "token_hash"))
                .containsExactlyInAnyOrder("rt-a-expirado-recente", "rt-a-valido");
        assertThat(valores("email_outbox", "subject"))
                .containsExactlyInAnyOrder("mail-sent-recente", "mail-failed-recente", "mail-pending-antigo");
    }

    // --- dados ---

    private static void dados(Connection c) throws SQLException {
        // Convites: expirados, aceites e revogados há muito tempo saem; recentes/válidos ficam.
        convite(c, TENANT_A, USER_A, "inv-a-expirado-antigo", agora().minusDays(40), null, null);
        convite(c, TENANT_A, USER_A, "inv-a-expirado-recente", agora().minusDays(1), null, null);
        convite(c, TENANT_A, USER_A, "inv-a-aceite-antigo", agora().plusDays(6), agora().minusDays(40), null);
        convite(c, TENANT_A, USER_A, "inv-a-revogado-antigo", agora().plusDays(6), null, agora().minusDays(40));
        convite(c, TENANT_A, USER_A, "inv-a-valido", agora().plusDays(6), null, null);
        convite(c, TENANT_B, USER_B, "inv-b-expirado-antigo", agora().minusDays(40), null, null);

        // Tokens de reposição: usados/expirados há muito tempo saem.
        reset(c, TENANT_A, USER_A, "prt-a-usado-antigo", agora().plusHours(1), agora().minusDays(10));
        reset(c, TENANT_A, USER_A, "prt-a-expirado-antigo", agora().minusDays(10), null);
        reset(c, TENANT_A, USER_A, "prt-a-expirado-recente", agora().minusDays(1), null);
        reset(c, TENANT_A, USER_A, "prt-a-valido", agora().plusHours(1), null);
        reset(c, TENANT_B, USER_B, "prt-b-expirado-antigo", agora().minusDays(10), null);

        // Refresh tokens: expirados/revogados há muito tempo saem.
        refresh(c, TENANT_A, USER_A, "rt-a-expirado-antigo", agora().minusDays(40), null);
        refresh(c, TENANT_A, USER_A, "rt-a-revogado-antigo", agora().plusDays(10), agora().minusDays(40));
        refresh(c, TENANT_A, USER_A, "rt-a-expirado-recente", agora().minusDays(1), null);
        refresh(c, TENANT_A, USER_A, "rt-a-valido", agora().plusDays(10), null);
        refresh(c, TENANT_B, USER_B, "rt-b-expirado-antigo", agora().minusDays(40), null);

        // Outbox: SENT/FAILED antigos saem; recentes e PENDING ficam (o PENDING ainda não foi enviado).
        outbox(c, "mail-sent-antigo", "SENT", agora().minusDays(40), agora().minusDays(40), agora().minusDays(40));
        outbox(c, "mail-sent-recente", "SENT", agora().minusDays(1), agora().minusDays(1), agora().minusDays(1));
        outbox(c, "mail-failed-antigo", "FAILED", agora().minusDays(40), null, agora().minusDays(40));
        outbox(c, "mail-failed-recente", "FAILED", agora().minusDays(1), null, agora().minusDays(1));
        outbox(c, "mail-pending-antigo", "PENDING", agora().minusDays(40), null, agora().plusDays(1));
    }

    private static void convite(Connection c, UUID tenant, UUID invitedBy, String token,
                                OffsetDateTime expires, OffsetDateTime accepted,
                                OffsetDateTime revoked) throws SQLException {
        executar(c, """
                INSERT INTO invitation (tenant_id, email, name, roles, token_hash, invited_by,
                                        expires_at, accepted_at, revoked_at)
                VALUES (?, 'convidado@test.local', 'Convidado', 'ATLETA', ?, ?, ?, ?, ?)
                """, tenant, token, invitedBy, expires, accepted, revoked);
    }

    private static void reset(Connection c, UUID tenant, UUID user, String token,
                              OffsetDateTime expires, OffsetDateTime used) throws SQLException {
        executar(c, """
                INSERT INTO password_reset_token (tenant_id, user_id, token_hash, expires_at, used_at)
                VALUES (?, ?, ?, ?, ?)
                """, tenant, user, token, expires, used);
    }

    private static void refresh(Connection c, UUID tenant, UUID user, String token,
                                OffsetDateTime expires, OffsetDateTime revoked) throws SQLException {
        executar(c, """
                INSERT INTO refresh_token (tenant_id, user_id, token_hash, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?)
                """, tenant, user, token, expires, revoked);
    }

    private static void outbox(Connection c, String subject, String status, OffsetDateTime createdAt,
                               OffsetDateTime sentAt, OffsetDateTime nextAttemptAt) throws SQLException {
        executar(c, """
                INSERT INTO email_outbox (recipient, subject, body, status, created_at, sent_at, next_attempt_at)
                VALUES ('dest@test.local', ?, 'corpo', ?, ?, ?, ?)
                """, subject, status, createdAt, sentAt, nextAttemptAt);
    }

    private static void clube(Connection c, UUID id, String nome, String slug) throws SQLException {
        executar(c, "INSERT INTO tenant (id, name, slug) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                id, nome, slug);
    }

    private static void utilizador(Connection c, UUID id, String email) throws SQLException {
        executar(c, "INSERT INTO app_user (id, email, name) VALUES (?, ?, 'Teste') ON CONFLICT DO NOTHING",
                id, email);
    }

    // --- auxiliares ---

    private static OffsetDateTime agora() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static List<String> valores(String table, String column) throws SQLException {
        List<String> valores = new ArrayList<>();
        try (Connection c = superuser();
             ResultSet rs = c.createStatement().executeQuery("SELECT " + column + " FROM " + table)) {
            while (rs.next()) {
                valores.add(rs.getString(1));
            }
        }
        return valores;
    }

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void executar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
