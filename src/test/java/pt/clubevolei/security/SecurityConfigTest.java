package pt.clubevolei.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class SecurityConfigTest {

    private static final byte[] SECRET = "unit-test-secret-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String ISSUER = "clube-volei-test";
    private static final String TENANT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private final SecurityConfig config = new SecurityConfig();
    private final JwtDecoder decoder = config.jwtDecoder(
            "", "", Base64.getEncoder().encodeToString(SECRET), ISSUER);

    private HttpServer oidcServer;
    private RSAKey signingKey;
    private String issuerUri;

    @BeforeEach
    void startOidcServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        oidcServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuerUri = "http://127.0.0.1:" + oidcServer.getAddress().getPort();
        oidcServer.createContext("/.well-known/openid-configuration", exchange -> respond(exchange,
                "{\"issuer\":\"" + issuerUri + "\",\"jwks_uri\":\"" + issuerUri + "/jwks\"}"));
        oidcServer.createContext("/jwks", exchange -> respond(exchange,
                new JWKSet(signingKey.toPublicJWK()).toString()));
        oidcServer.start();
    }

    @AfterEach
    void stopOidcServer() {
        if (oidcServer != null) {
            oidcServer.stop(0);
        }
    }

    @Test
    void validaAssinaturaIssuerEClaimsObrigatorios() throws Exception {
        Jwt jwt = decoder.decode(signedToken(payload(ISSUER, "user-123", now(), now() + 300)));

        assertThat(jwt.getSubject()).isEqualTo("user-123");
        assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("Valida JWT RS256 via descoberta OIDC e JWKS publico")
    void validaTokenComOidcIssuer() throws Exception {
        JwtDecoder oidcDecoder = config.jwtDecoder(issuerUri, "", "", "");

        Jwt jwt = oidcDecoder.decode(signedRsaToken(issuerUri));

        assertThat(jwt.getSubject()).isEqualTo("user-123");
        assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("Valida JWT RS256 com JWKS configurado diretamente")
    void validaTokenComJwkSetUri() throws Exception {
        JwtDecoder jwksDecoder = config.jwtDecoder("", issuerUri + "/jwks", "", issuerUri);

        Jwt jwt = jwksDecoder.decode(signedRsaToken(issuerUri));

        assertThat(jwt.getSubject()).isEqualTo("user-123");
    }

    @Test
    void rejeitaAssinaturaInvalida() throws Exception {
        String token = signedToken(payload(ISSUER, "user-123", now(), now() + 300));
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);

        assertThatThrownBy(() -> decoder.decode(String.join(".", parts)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejeitaIssuerDiferente() throws Exception {
        String token = signedToken(payload("outro-issuer", "user-123", now(), now() + 300));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Rejeita token sem exp")
    void exigeExpiracao() throws Exception {
        String token = signedToken(payload(ISSUER, "user-123", now(), null));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Rejeita token sem iat quando também não há exp (o decoder só injeta iat se houver exp)")
    void exigeEmitidoEmQuandoNaoHaExpiracao() throws Exception {
        String token = signedToken(payload(ISSUER, "user-123", null, null));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Rejeita token sem sub")
    void rejeitaSubjectEmFalta() throws Exception {
        String token = signedToken(payload(ISSUER, null, now(), now() + 300));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Rejeita token com sub em branco")
    void rejeitaSubjectEmBranco() throws Exception {
        String token = signedToken(payload(ISSUER, "   ", now(), now() + 300));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Falha cedo se o segredo não for Base64 válido")
    void rejeitaSecretBase64Invalido() {
        assertThatThrownBy(() -> config.jwtDecoder("", "", "***nao-e-base64***", ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Falha cedo se a chave HS256 tiver menos de 32 bytes")
    void rejeitaSecretDemasiadoCurto() {
        String shortSecret = Base64.getEncoder().encodeToString("demasiado-curto".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> config.jwtDecoder("", "", shortSecret, ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private String signedRsaToken(String issuer) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user-123")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tenant_id", TENANT_ID)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static String payload(String issuer, String subject, Long issuedAt, Long expiresAt) {
        StringBuilder payload = new StringBuilder("{");
        payload.append("\"iss\":\"").append(issuer).append("\"");
        if (subject != null) {
            payload.append(",\"sub\":\"").append(subject).append("\"");
        }
        if (issuedAt != null) {
            payload.append(",\"iat\":").append(issuedAt);
        }
        if (expiresAt != null) {
            payload.append(",\"exp\":").append(expiresAt);
        }
        payload.append(",\"tenant_id\":\"").append(TENANT_ID).append("\"}");
        return payload.toString();
    }

    private String signedToken(String payload) throws Exception {
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
}
