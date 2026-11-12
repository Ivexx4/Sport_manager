package pt.voleibol.gestao.maintenance;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retenção e periodicidade da limpeza de dados expirados.
 *
 * <p>Só é removido o que já não pode ser usado: convites aceites/revogados/expirados, tokens de
 * reposição usados/expirados, refresh tokens expirados/revogados e mensagens {@code SENT}/{@code FAILED}
 * da outbox. As retenções dão uma margem para diagnóstico antes de apagar o registo.
 */
@Validated
@ConfigurationProperties("app.maintenance.cleanup")
public record DataRetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT6H") Duration interval,
        @DefaultValue("PT1M") Duration initialDelay,
        @DefaultValue("P30D") Duration invitationRetention,
        @DefaultValue("P7D") Duration passwordResetRetention,
        @DefaultValue("P30D") Duration refreshTokenRetention,
        @DefaultValue("P30D") Duration outboxRetention) {

    public DataRetentionProperties {
        requirePositive(interval, "interval");
        requirePositive(initialDelay, "initial-delay");
        requirePositive(invitationRetention, "invitation-retention");
        requirePositive(passwordResetRetention, "password-reset-retention");
        requirePositive(refreshTokenRetention, "refresh-token-retention");
        requirePositive(outboxRetention, "outbox-retention");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "app.maintenance.cleanup." + name + " tem de ser uma duração positiva");
        }
    }
}
