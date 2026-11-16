package pt.voleibol.gestao.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Rules;

@RestController
@RequestMapping("/api/v1")
class AccountController {

    record InviteRequest(@NotBlank @Email @Size(max = 254) String email,
                         @NotBlank @Size(max = 120) String name,
                         @NotEmpty @Size(max = 6) Set<@NotBlank @Size(max = 20) String> roles) {
    }

    record AcceptInvitationRequest(@NotBlank @Size(max = 200) String token,
                                   @Size(max = 120) String name,
                                   @Size(max = 200) String password) {
    }

    record ForgotPasswordRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    record ResetPasswordRequest(@NotBlank @Size(max = 200) String token,
                                @NotBlank @Size(max = 200) String newPassword) {
    }

    record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
                                 @NotBlank @Size(max = 200) String newPassword) {
    }

    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** Só ADMIN e DIRECAO (regra também na cadeia de segurança); DIRECAO não atribui ADMIN nem DIRECAO. */
    @PostMapping("/users/invitations")
    ResponseEntity<Void> invite(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InviteRequest request) {
        accountService.invite(CurrentUser.of(jwt), request.email(), request.name(),
                request.roles());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/auth/invitations/accept")
    ResponseEntity<Void> acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        accountService.acceptInvitation(request.token(), request.name(), request.password());
        return ResponseEntity.noContent().build();
    }

    /** 202 exista ou não a conta. */
    @PostMapping("/auth/password/forgot")
    ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        accountService.forgotPassword(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/auth/password/reset")
    ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        accountService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/password/change")
    ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody ChangePasswordRequest request) {
        if (!accountService.changePassword(CurrentUser.of(jwt).id(), request.currentPassword(), request.newPassword()))
            throw Rules.badRequest("Password atual incorreta");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    List<AccountService.MemberView> listMembers() {
        return accountService.listMembers();
    }

    @PostMapping("/users/{userId}/disable")
    ResponseEntity<Void> disableMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        accountService.disableMember(CurrentUser.of(jwt), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{userId}/roles/{role}")
    ResponseEntity<Void> removeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                                    @PathVariable String role) {
        accountService.removeRole(CurrentUser.of(jwt), userId, role);
        return ResponseEntity.noContent().build();
    }
}
