package pt.voleibol.gestao.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import pt.voleibol.gestao.shared.Db;

/**
 * Estado do MFA na conta. Tal como o resto da identidade, corre dentro de uma transação com o tenant
 * definido: {@code app_user} só é visível para quem tem relação com o clube do pedido.
 *
 * <p>As alterações usam condições no próprio {@code UPDATE} ({@code mfa_enabled_at IS NULL},
 * {@code mfa_last_used_step < :step}) para que dois pedidos simultâneos não consigam ativar duas vezes
 * o MFA nem reutilizar o mesmo código: quem perde a corrida recebe zero linhas afetadas.
 */
@Repository
class MfaRepository {

    record State(UUID userId, String email, String secret, Instant pendingAt, Instant enabledAt) {
        boolean enabled() {
            return enabledAt != null;
        }

        boolean pending() {
            return secret != null && pendingAt != null && enabledAt == null;
        }
    }

    record Account(UUID userId, String email, String passwordHash) {
    }

    private final JdbcClient jdbc;

    MfaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<State> findState(UUID userId) {
        return jdbc.sql("""
                        SELECT id, email, mfa_secret, mfa_pending_at, mfa_enabled_at
                        FROM app_user WHERE id = :id
                        """)
                .param("id", userId)
                .query((rs, i) -> new State(Db.uuid(rs, "id"), rs.getString("email"),
                        rs.getString("mfa_secret"), Db.instant(rs, "mfa_pending_at"),
                        Db.instant(rs, "mfa_enabled_at")))
                .optional();
    }

    Optional<Account> findAccount(UUID userId) {
        return jdbc.sql("SELECT id, email, password_hash FROM app_user WHERE id = :id")
                .param("id", userId)
                .query((rs, i) -> new Account(Db.uuid(rs, "id"), rs.getString("email"),
                        rs.getString("password_hash")))
                .optional();
    }

    /** Inicia (ou reinicia) a inscrição com um segredo novo, sem mexer num MFA já ativo. */
    boolean startEnrollment(UUID userId, String secret) {
        return jdbc.sql("""
                        UPDATE app_user
                        SET mfa_secret = :secret, mfa_pending_at = now(), mfa_last_used_step = NULL,
                            updated_at = now()
                        WHERE id = :id AND mfa_enabled_at IS NULL
                        """)
                .param("id", userId).param("secret", secret)
                .update() == 1;
    }

    /** Confirma a inscrição pendente. Devolve false se entretanto já estava ativa. */
    boolean activate(UUID userId) {
        return jdbc.sql("""
                        UPDATE app_user
                        SET mfa_enabled_at = now(), mfa_pending_at = NULL, updated_at = now()
                        WHERE id = :id AND mfa_secret IS NOT NULL AND mfa_enabled_at IS NULL
                        """)
                .param("id", userId)
                .update() == 1;
    }

    /** Marca o passo como usado, mas só se for mais recente do que o último: trava a repetição. */
    boolean acceptStep(UUID userId, long step) {
        return jdbc.sql("""
                        UPDATE app_user SET mfa_last_used_step = :step, updated_at = now()
                        WHERE id = :id AND (mfa_last_used_step IS NULL OR mfa_last_used_step < :step)
                        """)
                .param("id", userId).param("step", step)
                .update() == 1;
    }

    /** Desliga o MFA e esquece o segredo. */
    void clear(UUID userId) {
        jdbc.sql("""
                        UPDATE app_user
                        SET mfa_secret = NULL, mfa_pending_at = NULL, mfa_enabled_at = NULL,
                            mfa_last_used_step = NULL, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", userId)
                .update();
    }

    void insertRecoveryCode(UUID userId, String codeHash) {
        jdbc.sql("INSERT INTO mfa_recovery_code (user_id, code_hash) VALUES (:user, :hash)")
                .param("user", userId).param("hash", codeHash)
                .update();
    }

    /** Consome o código de recuperação: só passa a usado se ainda não tinha sido usado. */
    boolean consumeRecoveryCode(UUID userId, String codeHash) {
        return jdbc.sql("""
                        UPDATE mfa_recovery_code SET used_at = now()
                        WHERE user_id = :user AND code_hash = :hash AND used_at IS NULL
                        """)
                .param("user", userId).param("hash", codeHash)
                .update() == 1;
    }

    int countUnusedRecoveryCodes(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM mfa_recovery_code WHERE user_id = :user AND used_at IS NULL")
                .param("user", userId)
                .query(Integer.class)
                .single();
    }

    void deleteRecoveryCodes(UUID userId) {
        jdbc.sql("DELETE FROM mfa_recovery_code WHERE user_id = :user").param("user", userId).update();
    }
}
