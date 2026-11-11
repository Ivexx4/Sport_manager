package pt.clubevolei.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String SECRET = "super-secret-key-that-is-at-least-512-bits-long-for-testing-purposes-and-hs512-compliance-123456";
    private JwtTokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new JwtTokenService(SECRET, 60);
    }

    @Test
    @DisplayName("Should generate token with correct subject, tenant_id, and claims")
    void shouldGenerateValidToken() {
        String userId = "usr-001";
        String email = "test@clube.pt";
        String tenantId = "tenant-abc";
        List<String> roles = List.of("ROLE_ADMIN", "ROLE_COACH");

        String token = tokenService.generateToken(userId, email, tenantId, roles);

        assertThat(token).isNotBlank();

        Claims claims = tokenService.validateAndExtractClaims(token);
        assertThat(claims.getSubject()).isEqualTo(userId);
        assertThat(claims.get("email", String.class)).isEqualTo(email);
        assertThat(claims.get("tenant_id", String.class)).isEqualTo(tenantId);

        @SuppressWarnings("unchecked")
        List<String> extractedRoles = claims.get("roles", List.class);
        assertThat(extractedRoles).containsExactlyElementsOf(roles);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Should reject token generation when userId is missing")
    void shouldRejectInvalidUserId(String invalidUserId) {
        assertThatThrownBy(() -> tokenService.generateToken(invalidUserId, "user@test.pt", "tenant-1", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID cannot be null or empty");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Should reject token generation when tenantId is missing")
    void shouldRejectInvalidTenantId(String invalidTenantId) {
        assertThatThrownBy(() -> tokenService.generateToken("usr-1", "user@test.pt", invalidTenantId, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tenant ID cannot be null or empty");
    }

    @Test
    @DisplayName("Should fail parsing if token signature is invalid or tampered")
    void shouldFailOnTamperedSignature() {
        String token = tokenService.generateToken("usr-1", "user@test.pt", "tenant-1", List.of());
        String tamperedToken = token + "maliciousExtraChar";

        assertThatThrownBy(() -> tokenService.validateAndExtractClaims(tamperedToken))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Should fail parsing if token is signed with different secret key")
    void shouldFailOnDifferentSecretKey() {
        String token = tokenService.generateToken("usr-1", "user@test.pt", "tenant-1", List.of());

        // Chave diferente com 512 bits / 64 bytes para evitar WeakKeyException
        String otherSecret = "another-super-secret-key-that-is-at-least-512-bits-long-for-testing-purposes-different";
        JwtTokenService otherService = new JwtTokenService(otherSecret, 60);

        assertThatThrownBy(() -> otherService.validateAndExtractClaims(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    @DisplayName("Should fail parsing if token is expired")
    void shouldFailOnExpiredToken() {
        JwtTokenService expiredTokenService = new JwtTokenService(SECRET, -1);
        String expiredToken = expiredTokenService.generateToken("usr-1", "user@test.pt", "tenant-1", List.of());

        assertThatThrownBy(() -> tokenService.validateAndExtractClaims(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }
}