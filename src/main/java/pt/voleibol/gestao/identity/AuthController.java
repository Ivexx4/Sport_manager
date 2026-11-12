package pt.voleibol.gestao.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Duration;
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

import pt.voleibol.gestao.tenancy.TenantContext;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 200) String password) {
    }

    record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
        static TokenResponse from(AuthService.Tokens tokens) {
            return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer",
                    tokens.expiresInSeconds());
        }
    }

    record MeResponse(String userId, String tenantId, List<String> roles) {
    }

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;

    AuthController(AuthService authService, LoginRateLimiter loginRateLimiter,
                   ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                        HttpServletRequest httpRequest) {
        UUID tenantId = TenantContext.current().orElse(null);
        String clientIp = clientIpResolver.resolve(httpRequest);

        Optional<Duration> retryAfter = loginRateLimiter.retryAfter(tenantId, clientIp);
        if (retryAfter.isPresent()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(retryAfter.get())))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        }

        Optional<AuthService.Tokens> tokens = authService.login(request.email(), request.password());
        if (tokens.isPresent()) {
            loginRateLimiter.recordSuccess(tenantId, clientIp);
        } else {
            loginRateLimiter.recordFailure(tenantId, clientIp);
        }
        return respond(tokens);
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return respond(authService.refresh(request.refreshToken()));
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

    private static ResponseEntity<TokenResponse> respond(java.util.Optional<AuthService.Tokens> tokens) {
        return tokens
                .map(t -> ResponseEntity.ok().header("Cache-Control", "no-store").body(TokenResponse.from(t)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private static long retryAfterSeconds(Duration duration) {
        return Math.max(1, (long) Math.ceil(duration.toMillis() / 1000.0));
    }
}
