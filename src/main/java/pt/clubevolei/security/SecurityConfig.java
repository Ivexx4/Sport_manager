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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import pt.clubevolei.tenancy.TenantFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // Executa depois da autorização: pedidos sem autenticação recebem 401 do Resource Server.
                .addFilterAfter(new TenantFilter(), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.security.jwt.secret-base64}") String secretBase64,
            @Value("${app.security.jwt.issuer}") String issuer) {
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
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> requiredClaims = jwt -> {
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
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), requiredClaims));
        return decoder;
    }
}
