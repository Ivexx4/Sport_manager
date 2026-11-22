package pt.voleibol.gestao.identity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuração da autenticação multifator. Por omissão os {@code ADMIN} são obrigados a inscrever-se:
 * é o perfil com mais poder dentro do clube. As restantes contas podem ativar o MFA, mas não são
 * forçadas.
 */
@Validated
@ConfigurationProperties("app.security.mfa")
public record MfaProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean requireForAdmins,
        @DefaultValue("5m") @NotNull Duration challengeTtl,
        @DefaultValue("1") @Min(0) @Max(5) int timeStepSkew,
        @DefaultValue("10") @Min(1) @Max(50) int recoveryCodeCount) {
}
