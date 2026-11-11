package pt.clubevolei.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;

@Service
public class JwtTokenService {

    private final SecretKey secretKey;
    private final long expirationMinutes;

    public JwtTokenService(
            @Value("${app.security.jwt.secret-base64:Y2x1YmUtdm9sZWktZGV2ZWxvcG1lbnQtc2VjcmV0LTIwMjY=}") String secretBase64,
            @Value("${jwt.expiration-minutes:120}") long expirationMinutes) {

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException e) {
            keyBytes = secretBase64.getBytes(StandardCharsets.UTF_8);
        }

        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT key must be at least 256 bits (32 bytes)");
        }

        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMinutes = expirationMinutes;
    }

    public String generateToken(String userId, String email, String tenantId, List<String> roles) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID cannot be null or empty");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(expirationMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("tenant_id", tenantId)
                .claim("roles", roles != null ? roles : List.of())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public Claims validateAndExtractClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }
}