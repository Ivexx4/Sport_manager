package pt.clubevolei.security;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import pt.clubevolei.tenancy.TenantFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/api/v1/auth/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterAfter(new TenantFilter(), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.security.oidc.issuer-uri:}") String oidcIssuerUri,
            @Value("${app.security.oidc.jwk-set-uri:}") String jwkSetUri,
            @Value("${app.security.jwt.secret-base64:}") String secretBase64,
            @Value("${app.security.jwt.issuer:}") String issuer) {
        if (StringUtils.hasText(oidcIssuerUri)) {
            return oidcDecoder(oidcIssuerUri);
        }
        if (StringUtils.hasText(jwkSetUri)) {
            return jwksDecoder(jwkSetUri, issuer);
        }
        return hs256Decoder(secretBase64, issuer);
    }

    private NimbusJwtDecoder oidcDecoder(String issuerUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(jwtValidator(issuerUri));
        return decoder;
    }

    private NimbusJwtDecoder jwksDecoder(String jwkSetUri, String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(jwtValidator(issuer));
        return decoder;
    }

    private NimbusJwtDecoder hs256Decoder(String secretBase64, String issuer) {
        final byte[] secret;
        try {
            secret = Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT_SECRET_BASE64 não contém Base64 válido", ex);
        }
        if (secret.length < 32) {
            throw new IllegalStateException("A chave JWT HS256 deve ter pelo menos 32 bytes (256 bits)");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(jwtValidator(issuer));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> jwtValidator(String issuer) {
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Claim sub obrigatório", null));
            }
            if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Claims iat e exp são obrigatórios", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), requiredClaims);
    }
}