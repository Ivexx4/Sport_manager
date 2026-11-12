package pt.voleibol.gestao.identity;

import jakarta.validation.constraints.NotBlank;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuração dos tokens. O segredo vem sempre do ambiente (JWT_SECRET), nunca do repositório:
 * 32 bytes ou mais, em Base64 (por exemplo, {@code openssl rand -base64 48}).
 */
@Validated
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @DefaultValue("gestao-clubes-voleibol") @NotBlank String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl) {
}
