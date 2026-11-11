package pt.clubevolei.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class SecurityConfigTest {

    private static final byte[] SECRET = "unit-test-secret-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String ISSUER = "clube-volei-test";
    private final JwtDecoder decoder = new SecurityConfig().jwtDecoder(
            Base64.getEncoder().encodeToString(SECRET), ISSUER);

    @Test
    void validaAssinaturaIssuerEClaimsObrigatorios() throws Exception {
        UUIDClaims claims = new UUIDClaims("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Jwt jwt = decoder.decode(signedToken(claims.tenantId(), ISSUER, true));

        assertThat(jwt.getSubject()).isEqualTo("user-123");
        assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo(claims.tenantId());
    }

    @Test
    void rejeitaAssinaturaInvalida() throws Exception {
        String token = signedToken("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", ISSUER, true);
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);

        assertThatThrownBy(() -> decoder.decode(String.join(".", parts)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejeitaIssuerDiferente() throws Exception {
        String token = signedToken("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "outro-issuer", true);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void exigeExpiracao() throws Exception {
        String token = signedToken("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", ISSUER, false);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private String signedToken(String tenantId, String issuer, boolean includeExpiration) throws Exception {
        long now = Instant.now().getEpochSecond();
        String payload = "{\"iss\":\"" + issuer + "\",\"sub\":\"user-123\",\"iat\":" + now
                + (includeExpiration ? ",\"exp\":" + (now + 300) : "")
                + ",\"tenant_id\":\"" + tenantId + "\"}";
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String unsigned = base64Url(header) + "." + base64Url(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        return unsigned + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record UUIDClaims(String tenantId) { }
}
