package pt.voleibol.gestao.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Login, rotação de refresh tokens e logout, sempre no contexto de um clube (o do subdomínio).
 *
 * <p>Os métodos devolvem vazio em vez de lançar exceções quando a autenticação falha: assim a
 * transação faz commit e o registo de auditoria da tentativa fica gravado.
 */
@Service
class AuthService {

    record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    /** Hash de uma password que ninguém conhece: mantém o tempo de resposta quando o email não existe. */
    private final String dummyHash;

    AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
                JwtProperties properties) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.dummyHash = passwordEncoder.encode(OpaqueTokens.newToken());
    }

    @Transactional
    Optional<Tokens> login(String email, String password) {
        UUID tenantId = requireTenant();

        Optional<AuthRepository.UserRow> user = repository.findActiveUserByEmail(email);
        String hash = user.map(AuthRepository.UserRow::passwordHash).orElse(dummyHash);
        if (hash == null || hash.isBlank()) {
            hash = dummyHash;
        }
        // Passwords com mais de 72 bytes nunca existem (ver PasswordPolicy); comparar na mesma, para manter o tempo.
        boolean fits = PasswordPolicy.fitsBcrypt(password);
        boolean passwordOk = passwordEncoder.matches(fits ? password : "", hash) && fits;

        Set<String> roles = passwordOk && user.isPresent()
                ? repository.findActiveRoles(tenantId, user.get().id())
                : Set.of();

        if (roles.isEmpty()) {
            // Mesma resposta para email desconhecido, password errada ou sem papel neste clube.
            repository.audit(tenantId, user.map(AuthRepository.UserRow::id).orElse(null), "LOGIN_FAILED");
            return Optional.empty();
        }

        UUID userId = user.get().id();
        repository.audit(tenantId, userId, "LOGIN_SUCCESS");
        return Optional.of(issueTokens(tenantId, userId, roles));
    }

    /** Rotação: cada refresh token só serve uma vez. Reutilizar um já usado revoga toda a sessão. */
    @Transactional
    Optional<Tokens> refresh(String rawRefreshToken) {
        UUID tenantId = requireTenant();

        Optional<AuthRepository.RefreshRow> found =
                repository.findRefreshToken(tenantId, OpaqueTokens.hash(rawRefreshToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AuthRepository.RefreshRow token = found.get();

        // A password mudou depois de este token ser emitido (inclui mudanças feitas noutros clubes).
        if (token.createdAt().isBefore(repository.findPasswordChangedAt(token.userId()))) {
            repository.revokeIfActive(tenantId, token.id());
            return Optional.empty();
        }

        boolean usedAlready = token.revoked() || !repository.revokeIfActive(tenantId, token.id());
        if (usedAlready) {
            repository.revokeAllForUser(tenantId, token.userId());
            repository.audit(tenantId, token.userId(), "REFRESH_TOKEN_REUSE");
            return Optional.empty();
        }
        if (token.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }

        // Os papéis são relidos: se o acesso foi retirado ao utilizador, o refresh deixa de funcionar.
        Set<String> roles = repository.findActiveRoles(tenantId, token.userId());
        if (roles.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(issueTokens(tenantId, token.userId(), roles));
    }

    @Transactional
    void logout(String rawRefreshToken) {
        UUID tenantId = requireTenant();
        repository.findRefreshToken(tenantId, OpaqueTokens.hash(rawRefreshToken)).ifPresent(token -> {
            if (repository.revokeIfActive(tenantId, token.id())) {
                repository.audit(tenantId, token.userId(), "LOGOUT");
            }
        });
    }

    private Tokens issueTokens(UUID tenantId, UUID userId, Set<String> roles) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(TenantClaimValidator.CLAIM, tenantId.toString())
                .claim("roles", new TreeSet<>(roles).stream().toList())
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        String refreshToken = OpaqueTokens.newToken();
        repository.insertRefreshToken(tenantId, userId, OpaqueTokens.hash(refreshToken),
                now.plus(properties.refreshTokenTtl()));

        return new Tokens(accessToken, refreshToken, properties.accessTokenTtl().toSeconds());
    }

    static UUID requireTenant() {
        return TenantContext.current().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube: usa o subdomínio do clube"));
    }
}
