package pt.voleibol.gestao.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 200) String password) {
    }

    record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    /**
     * Resposta de um login que ainda não terminou. {@code mfaRequired} pede o código da aplicação
     * autenticadora; {@code mfaEnrollmentRequired} pede a inscrição (o perfil exige MFA e a conta
     * ainda não o tem). Em qualquer dos casos, a sessão só abre no passo seguinte.
     */
    record MfaChallengeResponse(boolean mfaRequired, boolean mfaEnrollmentRequired, String challengeToken,
                                long expiresIn) {
        static MfaChallengeResponse from(MfaService.Challenge challenge) {
            return new MfaChallengeResponse(challenge.flow() == MfaService.Flow.VERIFY,
                    challenge.flow() == MfaService.Flow.ENROLL, challenge.token(), challenge.expiresInSeconds());
        }
    }

    record MeResponse(String userId, String tenantId, List<String> roles) {
    }

    record MemberInfo(UUID tenantId, String slug, String name, List<String> roles) {
        static MemberInfo from(AuthService.MemberInfo m) {
            return new MemberInfo(m.tenantId(), m.slug(), m.name(), List.copyOf(m.roles()));
        }
    }

    private final AuthService authService;
    private final AuthThrottle throttle;

    AuthController(AuthService authService, AuthThrottle throttle) {
        this.authService = authService;
        this.throttle = throttle;
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        Optional<ResponseEntity<Void>> blocked = throttle.blocked(httpRequest);
        if (blocked.isPresent()) return blocked.get();

        Optional<AuthService.LoginOutcome> outcome = authService.login(request.email(), request.password());
        if (outcome.isEmpty()) {
            throttle.record(httpRequest, false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        AuthService.LoginOutcome result = outcome.get();
        if (result.challenge() != null) {
            // A password estava certa, mas a sessão ainda não começou: as tentativas continuam a
            // contar (e são limpas) no segundo passo.
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(MfaChallengeResponse.from(result.challenge()));
        }
        throttle.record(httpRequest, true);
        return TokenResponse.ok(result.tokens());
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken())
                .map(TokenResponse::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new MeResponse(jwt.getSubject(), jwt.getClaimAsString(TenantClaimValidator.CLAIM),
                roles == null ? List.of() : roles);
    }

    @GetMapping("/memberships")
    List<MemberInfo> memberships(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return authService.listMemberships(userId).stream().map(MemberInfo::from).toList();
    }
}
