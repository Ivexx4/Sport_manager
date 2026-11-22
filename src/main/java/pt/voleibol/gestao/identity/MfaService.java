package pt.voleibol.gestao.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pt.voleibol.gestao.shared.AuditLog;
import pt.voleibol.gestao.shared.Role;
import pt.voleibol.gestao.shared.Rules;

/**
 * Autenticação multifator por TOTP.
 *
 * <p>O login em duas etapas não usa uma tabela de desafios: depois de validar a password, o serviço
 * emite um token de curta duração assinado com a mesma chave do JWT, marcado com
 * {@code purpose=mfa_challenge} e {@code mfa_flow=VERIFY|ENROLL}. Esse token só serve para completar
 * a autenticação — {@link TenantClaimValidator} recusa tokens com o claim {@code purpose}, por isso um
 * desafio nunca é aceite como token de acesso.
 *
 * <p>O código de recuperação é de uso único e vale em qualquer clube; por isso vive numa tabela de
 * plataforma (sem RLS), com o hash em vez do código.
 */
@Service
class MfaService {

    static final String PURPOSE_CLAIM = "purpose";
    static final String PURPOSE_VALUE = "mfa_challenge";
    static final String FLOW_CLAIM = "mfa_flow";

    private static final char[] RECOVERY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int RECOVERY_CODE_LENGTH = 10;
    private static final int RECOVERY_GROUP = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    enum Flow {
        /** A conta tem MFA ativo: falta o segundo fator. */
        VERIFY,
        /** A conta é obrigada a ter MFA e ainda não o ativou. */
        ENROLL
    }

    record Challenge(UUID userId, Flow flow, String token, long expiresInSeconds) {
    }

    /** Desafio já validado: quem é o utilizador e o que lhe falta fazer. */
    record Claims(UUID userId, Flow flow) {
    }

    record Enrollment(String secret, String otpauthUri) {
    }

    record Activation(List<String> recoveryCodes) {
    }

    record Status(boolean enabled, boolean pending, int recoveryCodesLeft) {
    }

    private final MfaRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final MfaProperties properties;
    private final AuditLog audit;
    /**
     * Descodificador próprio: o descodificador dos tokens de acesso recusa o claim {@code purpose},
     * e é isso que impede usar um desafio como se fosse um token de sessão.
     */
    private final NimbusJwtDecoder challengeDecoder;

    MfaService(MfaRepository repository, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
               SecretKey jwtSecretKey, JwtProperties jwtProperties, MfaProperties properties,
               AuditLog audit) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.properties = properties;
        this.audit = audit;
        this.challengeDecoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.challengeDecoder.setJwtValidator(
                JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));
    }

    /**
     * Decide o que falta a quem acabou de acertar na password: nada, um código TOTP ou uma inscrição.
     */
    Optional<Challenge> gate(UUID tenantId, UUID userId, Set<Role> roles) {
        if (!properties.enabled()) return Optional.empty();
        Optional<MfaRepository.State> found = repository.findState(userId);
        if (found.isEmpty()) return Optional.empty();
        if (found.get().enabled()) return Optional.of(challenge(tenantId, userId, Flow.VERIFY));
        if (properties.requireForAdmins() && roles.contains(Role.ADMIN)) {
            return Optional.of(challenge(tenantId, userId, Flow.ENROLL));
        }
        return Optional.empty();
    }

    /** Valida o token de desafio e confirma que pertence ao clube do pedido. */
    Optional<Claims> parse(UUID tenantId, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        Jwt jwt;
        try {
            jwt = challengeDecoder.decode(rawToken);
        } catch (JwtException e) {
            return Optional.empty();
        }
        if (!PURPOSE_VALUE.equals(jwt.getClaimAsString(PURPOSE_CLAIM))) return Optional.empty();
        if (!tenantId.toString().equals(jwt.getClaimAsString(TenantClaimValidator.CLAIM))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Claims(UUID.fromString(jwt.getSubject()),
                    Flow.valueOf(jwt.getClaimAsString(FLOW_CLAIM))));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** Gera um segredo novo e deixa a inscrição pendente à espera do primeiro código. */
    @Transactional
    Enrollment startEnrollment(UUID tenantId, UUID userId) {
        MfaRepository.State state = repository.findState(userId)
                .orElseThrow(() -> Rules.notFound("Conta inexistente neste clube"));
        if (state.enabled()) throw Rules.conflict("O MFA já está ativo nesta conta. Desativa-o primeiro.");

        String secret = Totp.newSecret();
        if (!repository.startEnrollment(userId, secret)) {
            throw Rules.conflict("Não foi possível iniciar a inscrição do MFA");
        }
        audit.record(tenantId, userId, "MFA_ENROLLMENT_STARTED");
        return new Enrollment(secret, otpauthUri(state.email(), secret));
    }

    /**
     * Confirma a inscrição com o primeiro código da aplicação autenticadora e devolve os códigos de
     * recuperação (em claro, uma única vez).
     */
    @Transactional
    Optional<Activation> confirmEnrollment(UUID tenantId, UUID userId, String code) {
        Optional<MfaRepository.State> found = repository.findState(userId);
        if (found.isEmpty() || !found.get().pending()) return Optional.empty();

        OptionalLong step = Totp.verify(found.get().secret(), code, Instant.now().getEpochSecond(),
                properties.timeStepSkew());
        if (step.isEmpty() || !repository.activate(userId)) {
            audit.record(tenantId, userId, "MFA_ENROLLMENT_FAILED");
            return Optional.empty();
        }
        // O código que confirmou a inscrição também não pode ser reapresentado no login seguinte.
        repository.acceptStep(userId, step.getAsLong());

        List<String> codes = issueRecoveryCodes(userId);
        audit.record(tenantId, userId, "MFA_ENROLLED");
        return Optional.of(new Activation(codes));
    }

    /** Segundo fator do login: TOTP ou, em alternativa, um código de recuperação por usar. */
    @Transactional
    boolean verify(UUID tenantId, UUID userId, String code) {
        Optional<MfaRepository.State> found = repository.findState(userId);
        if (found.isEmpty() || !found.get().enabled() || !codeAccepted(userId, found.get(), code)) {
            audit.record(tenantId, userId, "MFA_VERIFICATION_FAILED");
            return false;
        }
        audit.record(tenantId, userId, "MFA_VERIFIED");
        return true;
    }

    /** Desliga o MFA: exige a password e um código válido (a sessão roubada não chega). */
    @Transactional
    boolean disable(UUID tenantId, UUID userId, String password, String code) {
        Optional<MfaRepository.Account> account = repository.findAccount(userId);
        Optional<MfaRepository.State> state = repository.findState(userId);
        if (account.isEmpty() || state.isEmpty() || !state.get().enabled()
                || !passwordMatches(password, account.get().passwordHash())
                || !codeAccepted(userId, state.get(), code)) {
            audit.record(tenantId, userId, "MFA_DISABLE_FAILED");
            return false;
        }
        repository.clear(userId);
        repository.deleteRecoveryCodes(userId);
        audit.record(tenantId, userId, "MFA_DISABLED");
        return true;
    }

    @Transactional(readOnly = true)
    Status status(UUID userId) {
        MfaRepository.State state = repository.findState(userId)
                .orElseThrow(() -> Rules.notFound("Conta inexistente neste clube"));
        int left = state.enabled() ? repository.countUnusedRecoveryCodes(userId) : 0;
        return new Status(state.enabled(), state.pending(), left);
    }

    private boolean codeAccepted(UUID userId, MfaRepository.State state, String code) {
        if (state.secret() != null) {
            OptionalLong step = Totp.verify(state.secret(), code, Instant.now().getEpochSecond(),
                    properties.timeStepSkew());
            if (step.isPresent()) return repository.acceptStep(userId, step.getAsLong());
        }
        return consumeRecoveryCode(userId, code);
    }

    private boolean consumeRecoveryCode(UUID userId, String rawCode) {
        String normalized = normalizeRecoveryCode(rawCode);
        return normalized != null && repository.consumeRecoveryCode(userId, OpaqueTokens.hash(normalized));
    }

    private List<String> issueRecoveryCodes(UUID userId) {
        repository.deleteRecoveryCodes(userId);
        List<String> codes = new ArrayList<>(properties.recoveryCodeCount());
        for (int i = 0; i < properties.recoveryCodeCount(); i++) {
            String code = newRecoveryCode();
            repository.insertRecoveryCode(userId, OpaqueTokens.hash(code.replace("-", "")));
            codes.add(code);
        }
        return List.copyOf(codes);
    }

    private boolean passwordMatches(String password, String hash) {
        boolean fits = PasswordPolicy.fitsBcrypt(password);
        return hash != null && !hash.isBlank() && passwordEncoder.matches(fits ? password : "", hash) && fits;
    }

    private Challenge challenge(UUID tenantId, UUID userId, Flow flow) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.challengeTtl()))
                .claim(TenantClaimValidator.CLAIM, tenantId.toString())
                .claim(PURPOSE_CLAIM, PURPOSE_VALUE)
                .claim(FLOW_CLAIM, flow.name())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Challenge(userId, flow, token, properties.challengeTtl().toSeconds());
    }

    /** URI que a aplicação autenticadora lê do QR code (ou se introduz à mão). */
    private String otpauthUri(String email, String secret) {
        String issuer = jwtProperties.issuer();
        return "otpauth://totp/" + encode(issuer + ":" + email)
                + "?secret=" + secret
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + Totp.DIGITS
                + "&period=" + Totp.PERIOD_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String newRecoveryCode() {
        StringBuilder code = new StringBuilder(RECOVERY_CODE_LENGTH + 1);
        for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
            if (i == RECOVERY_GROUP) code.append('-');
            code.append(RECOVERY_ALPHABET[RANDOM.nextInt(RECOVERY_ALPHABET.length)]);
        }
        return code.toString();
    }

    /** Aceita o código com ou sem hífen e em minúsculas, mas exige os 10 caracteres. */
    private static String normalizeRecoveryCode(String code) {
        if (code == null) return null;
        StringBuilder out = new StringBuilder(RECOVERY_CODE_LENGTH);
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean ascii = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (ascii) out.append(Character.toUpperCase(c));
        }
        return out.length() == RECOVERY_CODE_LENGTH ? out.toString() : null;
    }
}
