package pt.voleibol.gestao.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pt.voleibol.gestao.shared.AuditLog;
import pt.voleibol.gestao.shared.Role;
import pt.voleibol.gestao.shared.Rules;

/**
 * Login, rotação de refresh tokens e logout, sempre no contexto de um clube (o do subdomínio).
 *
 * <p>Os métodos devolvem vazio em vez de lançar exceções quando a autenticação falha: assim a
 * transação faz commit e o registo de auditoria da tentativa fica gravado.
 *
 * <p>Com MFA ativo, o login só fica completo depois do segundo fator: a password certa devolve um
 * desafio, não tokens. Os tokens continuam a ser emitidos apenas aqui, para haver um único caminho.
 */
@Service
class AuthService {

    record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    /** Resultado do login: ou os tokens, ou o passo que ainda falta (desafio de MFA). */
    record LoginOutcome(Tokens tokens, MfaService.Challenge challenge) {
    }

    /** Inscrição de MFA confirmada já durante o login: sessão aberta e códigos para guardar. */
    record Enrollment(Tokens tokens, List<String> recoveryCodes) {
    }

    /** Clube onde o utilizador tem membresia ativa, com o papel correspondente. */
    record MemberInfo(UUID tenantId, String slug, String name, Set<String> roles) {
    }

    private final AuthRepository repository;
    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final AuditLog audit;
    /** Hash de uma password que ninguém conhece: mantém o tempo de resposta quando o email não existe. */
    private final String dummyHash;

    AuthService(AuthRepository repository, MfaService mfaService, PasswordEncoder passwordEncoder,
                JwtEncoder jwtEncoder, JwtProperties properties, AuditLog audit) {
        this.repository = repository;
        this.mfaService = mfaService;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode(OpaqueTokens.newToken());
    }

    @Transactional
    Optional<LoginOutcome> login(String email, String password) {
        UUID tenantId = Rules.tenant();

        Optional<AuthRepository.UserRow> user = repository.findActiveUserByEmail(email);
        String hash = user.map(AuthRepository.UserRow::passwordHash).orElse(dummyHash);
        if (hash == null || hash.isBlank()) hash = dummyHash;
        // Passwords com mais de 72 bytes nunca existem (ver PasswordPolicy); comparar na mesma, para manter o tempo.
        boolean fits = PasswordPolicy.fitsBcrypt(password);
        boolean passwordOk = passwordEncoder.matches(fits ? password : "", hash) && fits;

        Set<Role> roles = passwordOk && user.isPresent()
                ? Role.fromAll(repository.findActiveRoles(tenantId, user.get().id()))
                : Set.of();

        if (roles.isEmpty()) {
            // Mesma resposta para email desconhecido, password errada ou sem papel neste clube.
            audit.record(tenantId, user.map(AuthRepository.UserRow::id).orElse(null), "LOGIN_FAILED");
            return Optional.empty();
        }

        UUID userId = user.get().id();
        Optional<MfaService.Challenge> challenge = mfaService.gate(tenantId, userId, roles);
        if (challenge.isPresent()) {
            audit.record(tenantId, userId,
                    challenge.get().flow() == MfaService.Flow.ENROLL ? "MFA_ENROLLMENT_REQUIRED"
                            : "MFA_CHALLENGE_ISSUED");
            return Optional.of(new LoginOutcome(null, challenge.get()));
        }

        audit.record(tenantId, userId, "LOGIN_SUCCESS");
        return Optional.of(new LoginOutcome(issueTokens(tenantId, userId, roles), null));
    }

    /** Segundo fator do login, a partir do desafio emitido pelo {@link #login}. */
    @Transactional
    Optional<Tokens> verifyMfa(String rawChallenge, String code) {
        UUID tenantId = Rules.tenant();
        Optional<MfaService.Claims> claims = mfaService.parse(tenantId, rawChallenge);
        if (claims.isEmpty() || claims.get().flow() != MfaService.Flow.VERIFY) return Optional.empty();

        UUID userId = claims.get().userId();
        // Os papéis são relidos: um acesso retirado entretanto já não dá sessão.
        Set<Role> roles = Role.fromAll(repository.findActiveRoles(tenantId, userId));
        if (roles.isEmpty() || !mfaService.verify(tenantId, userId, code)) return Optional.empty();

        audit.record(tenantId, userId, "LOGIN_SUCCESS");
        return Optional.of(issueTokens(tenantId, userId, roles));
    }

    /** Inscrição de MFA obrigatória (ADMIN) concluída dentro do login. */
    @Transactional
    Optional<Enrollment> confirmMfaEnrollment(String rawChallenge, String code) {
        UUID tenantId = Rules.tenant();
        Optional<MfaService.Claims> claims = mfaService.parse(tenantId, rawChallenge);
        if (claims.isEmpty() || claims.get().flow() != MfaService.Flow.ENROLL) return Optional.empty();

        UUID userId = claims.get().userId();
        Set<Role> roles = Role.fromAll(repository.findActiveRoles(tenantId, userId));
        if (roles.isEmpty()) return Optional.empty();

        Optional<MfaService.Activation> activation = mfaService.confirmEnrollment(tenantId, userId, code);
        if (activation.isEmpty()) return Optional.empty();

        audit.record(tenantId, userId, "LOGIN_SUCCESS");
        return Optional.of(new Enrollment(issueTokens(tenantId, userId, roles),
                activation.get().recoveryCodes()));
    }

    /** Rotação: cada refresh token só serve uma vez. Reutilizar um já usado revoga toda a sessão. */
    @Transactional
    Optional<Tokens> refresh(String rawRefreshToken) {
        UUID tenantId = Rules.tenant();

        Optional<AuthRepository.RefreshRow> found =
                repository.findRefreshToken(tenantId, OpaqueTokens.hash(rawRefreshToken));
        if (found.isEmpty()) return Optional.empty();
        AuthRepository.RefreshRow token = found.get();

        // A password mudou depois de este token ser emitido (inclui mudanças feitas noutros clubes).
        if (token.createdAt().isBefore(repository.findPasswordChangedAt(token.userId()))) {
            repository.revokeIfActive(tenantId, token.id());
            return Optional.empty();
        }

        boolean usedAlready = token.revoked() || !repository.revokeIfActive(tenantId, token.id());
        if (usedAlready) {
            repository.revokeAllForUser(tenantId, token.userId());
            audit.record(tenantId, token.userId(), "REFRESH_TOKEN_REUSE");
            return Optional.empty();
        }
        if (token.expiresAt().isBefore(Instant.now())) return Optional.empty();

        // Os papéis são relidos: se o acesso foi retirado ao utilizador, o refresh deixa de funcionar.
        Set<Role> roles = Role.fromAll(repository.findActiveRoles(tenantId, token.userId()));
        if (roles.isEmpty()) return Optional.empty();
        return Optional.of(issueTokens(tenantId, token.userId(), roles));
    }

    @Transactional
    void logout(String rawRefreshToken) {
        UUID tenantId = Rules.tenant();
        repository.findRefreshToken(tenantId, OpaqueTokens.hash(rawRefreshToken)).ifPresent(token -> {
            if (repository.revokeIfActive(tenantId, token.id())) audit.record(tenantId, token.userId(), "LOGOUT");
        });
    }

    /** Todos os clubes onde o utilizador é membro ativo — chamado no primeiro passo do login. */
    @Transactional(readOnly = true)
    List<MemberInfo> listMemberships(UUID userId) {
        return repository.findMembershipsWithRoles(userId).stream()
                .map(m -> new MemberInfo(m.tenantId(), m.slug(), m.name(), Set.of(m.role())))
                .toList();
    }

    private Tokens issueTokens(UUID tenantId, UUID userId, Set<Role> roles) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(TenantClaimValidator.CLAIM, tenantId.toString())
                .claim("roles", new TreeSet<>(Role.codes(roles)).stream().toList())
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        String refreshToken = OpaqueTokens.newToken();
        repository.insertRefreshToken(tenantId, userId, OpaqueTokens.hash(refreshToken),
                now.plus(properties.refreshTokenTtl()));

        return new Tokens(accessToken, refreshToken, properties.accessTokenTtl().toSeconds());
    }

}
