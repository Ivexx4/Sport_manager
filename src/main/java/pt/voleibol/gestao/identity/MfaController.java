package pt.voleibol.gestao.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Rules;

/**
 * Autenticação multifator.
 *
 * <p>Os três primeiros endpoints são públicos: recebem o token de desafio devolvido pelo login e são
 * a única forma de completar uma sessão quando o MFA está ativo. Os restantes exigem sessão aberta e
 * servem para a pessoa gerir o MFA da própria conta.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
class MfaController {

    /** Desafio + código de 6 dígitos (ou um código de recuperação). */
    record CodeRequest(@NotBlank @Size(max = 4000) String challengeToken,
                       @NotBlank @Size(max = 64) String code) {
    }

    record ChallengeRequest(@NotBlank @Size(max = 4000) String challengeToken) {
    }

    record ActivateRequest(@NotBlank @Size(max = 64) String code) {
    }

    record DisableRequest(@NotBlank @Size(max = 200) String password,
                          @NotBlank @Size(max = 64) String code) {
    }

    /** Sessão aberta depois de confirmar a inscrição; os códigos só aparecem nesta resposta. */
    record EnrollmentResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                              List<String> recoveryCodes) {
        static EnrollmentResponse from(AuthService.Enrollment enrollment) {
            AuthService.Tokens tokens = enrollment.tokens();
            return new EnrollmentResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer",
                    tokens.expiresInSeconds(), enrollment.recoveryCodes());
        }
    }

    record SetupResponse(String secret, String otpauthUri) {
        static SetupResponse from(MfaService.Enrollment enrollment) {
            return new SetupResponse(enrollment.secret(), enrollment.otpauthUri());
        }
    }

    record ActivationResponse(List<String> recoveryCodes) {
    }

    record StatusResponse(boolean enabled, boolean pending, int recoveryCodesLeft) {
    }

    private final AuthService authService;
    private final MfaService mfaService;
    private final AuthThrottle throttle;

    MfaController(AuthService authService, MfaService mfaService, AuthThrottle throttle) {
        this.authService = authService;
        this.mfaService = mfaService;
        this.throttle = throttle;
    }

    // --- segundo passo do login (sem sessão) ---

    @PostMapping("/verify")
    ResponseEntity<?> verify(@Valid @RequestBody CodeRequest request, HttpServletRequest httpRequest) {
        Optional<ResponseEntity<Void>> blocked = throttle.blocked(httpRequest);
        if (blocked.isPresent()) return blocked.get();

        Optional<AuthService.Tokens> tokens = authService.verifyMfa(request.challengeToken(), request.code());
        throttle.record(httpRequest, tokens.isPresent());
        return tokens.<ResponseEntity<?>>map(TokenResponse::ok).orElseGet(MfaController::unauthorized);
    }

    @PostMapping("/enrollment/setup")
    ResponseEntity<?> enrollmentSetup(@Valid @RequestBody ChallengeRequest request) {
        UUID tenantId = Rules.tenant();
        Optional<MfaService.Claims> claims = mfaService.parse(tenantId, request.challengeToken());
        if (claims.isEmpty() || claims.get().flow() != MfaService.Flow.ENROLL) return unauthorized();

        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(SetupResponse.from(mfaService.startEnrollment(tenantId, claims.get().userId())));
    }

    @PostMapping("/enrollment/confirm")
    ResponseEntity<?> enrollmentConfirm(@Valid @RequestBody CodeRequest request, HttpServletRequest httpRequest) {
        Optional<ResponseEntity<Void>> blocked = throttle.blocked(httpRequest);
        if (blocked.isPresent()) return blocked.get();

        Optional<AuthService.Enrollment> enrollment =
                authService.confirmMfaEnrollment(request.challengeToken(), request.code());
        throttle.record(httpRequest, enrollment.isPresent());
        return enrollment
                .<ResponseEntity<?>>map(e -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-store").body(EnrollmentResponse.from(e)))
                .orElseGet(MfaController::unauthorized);
    }

    // --- gestão do MFA da própria conta (com sessão) ---

    @GetMapping
    StatusResponse status(CurrentUser user) {
        MfaService.Status status = mfaService.status(user.id());
        return new StatusResponse(status.enabled(), status.pending(), status.recoveryCodesLeft());
    }

    /** Início da inscrição opcional, para quem já tem sessão (por exemplo, um TREINADOR). */
    @PostMapping("/setup")
    SetupResponse setup(CurrentUser user) {
        return SetupResponse.from(mfaService.startEnrollment(Rules.tenant(), user.id()));
    }

    @PostMapping("/activate")
    ActivationResponse activate(CurrentUser user, @Valid @RequestBody ActivateRequest request) {
        MfaService.Activation activation = mfaService
                .confirmEnrollment(Rules.tenant(), user.id(), request.code())
                .orElseThrow(() -> Rules.badRequest("Código inválido ou inscrição não iniciada"));
        return new ActivationResponse(activation.recoveryCodes());
    }

    @PostMapping("/disable")
    ResponseEntity<Void> disable(CurrentUser user, @Valid @RequestBody DisableRequest request) {
        if (!mfaService.disable(Rules.tenant(), user.id(), request.password(), request.code())) {
            throw Rules.forbidden("Password ou código inválido");
        }
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Void> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
