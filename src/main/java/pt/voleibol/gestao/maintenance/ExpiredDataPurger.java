package pt.voleibol.gestao.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remove dados expirados de todos os clubes numa só chamada à função {@code purge_expired_data}.
 *
 * <p>A função corre como dono das tabelas ({@code SECURITY DEFINER}) porque as tabelas multi-tenant
 * têm RLS forçada: sem isso, uma tarefa sem tenant não veria (nem apagaria) nada. Os limites são
 * calculados a partir das retenções configuradas e a função limita-os a "agora", pelo que nunca
 * apaga um token/convite que ainda seja válido.
 *
 * <p>Corre com pouca frequência (por omissão a cada 6 horas); a limpeza é uma operação de
 * manutenção e não deve competir com o tráfego normal.
 */
@Component
public class ExpiredDataPurger {

    private static final Logger log = LoggerFactory.getLogger(ExpiredDataPurger.class);

    public record Purged(long invitations, long passwordResets, long refreshTokens, long outbox) {

        boolean nothing() {
            return invitations == 0 && passwordResets == 0 && refreshTokens == 0 && outbox == 0;
        }
    }

    private final JdbcClient jdbc;
    private final DataRetentionProperties properties;
    private final Clock clock;

    @Autowired
    ExpiredDataPurger(JdbcClient jdbc, DataRetentionProperties properties) {
        this(jdbc, properties, Clock.systemUTC());
    }

    /** Construtor usado nos testes, para controlar o tempo sem esperar. */
    ExpiredDataPurger(JdbcClient jdbc, DataRetentionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Purged purge() {
        if (!properties.enabled()) {
            return new Purged(0, 0, 0, 0);
        }

        Instant now = clock.instant();
        Purged purged = jdbc.sql("""
                        SELECT invitations_deleted,
                               resets_deleted,
                               refresh_tokens_deleted,
                               outbox_deleted
                        FROM purge_expired_data(:invitations, :resets, :refreshTokens, :outbox)
                        """)
                .param("invitations", cutoff(now, properties.invitationRetention()))
                .param("resets", cutoff(now, properties.passwordResetRetention()))
                .param("refreshTokens", cutoff(now, properties.refreshTokenRetention()))
                .param("outbox", cutoff(now, properties.outboxRetention()))
                .query((rs, i) -> new Purged(
                        rs.getLong("invitations_deleted"),
                        rs.getLong("resets_deleted"),
                        rs.getLong("refresh_tokens_deleted"),
                        rs.getLong("outbox_deleted")))
                .single();

        if (purged.nothing()) {
            log.debug("Limpeza periódica sem dados a remover");
        } else {
            log.info("Limpeza periódica: {} convites, {} tokens de reposição, {} refresh tokens "
                            + "e {} mensagens da outbox removidos",
                    purged.invitations(), purged.passwordResets(), purged.refreshTokens(), purged.outbox());
        }
        return purged;
    }

    private static OffsetDateTime cutoff(Instant now, Duration retention) {
        return OffsetDateTime.ofInstant(now.minus(retention), ZoneOffset.UTC);
    }
}
