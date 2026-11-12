package pt.voleibol.gestao.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.notifications.EmailSender;

/**
 * Convites de utilizadores, aceitação, recuperação e alteração de password, sempre no contexto de um clube.
 *
 * <p>Os e-mails são enfileirados na outbox na mesma transação: se a operação falhar, nada é enviado.
 */
@Service
class AccountService {

    private static final Set<String> ROLES =
            Set.of("ADMIN", "DIRECAO", "FINANCEIRO", "TREINADOR", "ATLETA", "ENCARREGADO");
    /** Papéis que dão poder de gestão: só um ADMIN os pode atribuir. */
    private static final Set<String> PRIVILEGED = Set.of("ADMIN", "DIRECAO");

    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final Duration RESET_TTL = Duration.ofHours(1);
    private static final int MAX_RESETS_PER_HOUR = 3;

    private final AccountRepository accounts;
    private final AuthRepository auth;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final String webUrlTemplate;

    AccountService(AccountRepository accounts, AuthRepository auth, PasswordEncoder passwordEncoder,
                   EmailSender emailSender, @Value("${app.links.web-url-template}") String webUrlTemplate) {
        this.accounts = accounts;
        this.auth = auth;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.webUrlTemplate = webUrlTemplate;
    }

    // --- convites ---

    @Transactional
    void invite(UUID inviterId, Set<String> inviterRoles, String email, String name, Set<String> requestedRoles) {
        UUID tenantId = AuthService.requireTenant();

        if (inviterRoles.stream().noneMatch(PRIVILEGED::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Set<String> roles = requestedRoles.stream()
                .map(r -> r.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
        if (roles.isEmpty() || !ROLES.containsAll(roles)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Papel inválido");
        }
        if (!inviterRoles.contains("ADMIN") && roles.stream().anyMatch(PRIVILEGED::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só um administrador atribui este papel");
        }

        String mail = normalizeEmail(email);
        accounts.revokePendingInvitations(tenantId, mail);   // um convite pendente por email
        String rawToken = OpaqueTokens.newToken();
        accounts.insertInvitation(tenantId, mail, name.trim(), String.join(",", roles),
                OpaqueTokens.hash(rawToken), inviterId, Instant.now().plus(INVITATION_TTL));
        auth.audit(tenantId, inviterId, "USER_INVITED");

        AccountRepository.TenantInfo tenant = accounts.findTenant(tenantId).orElseThrow();
        String link = link(tenant.slug(), "/accept-invitation", rawToken);
        String body = """
                Olá %s,

                Foste convidado(a) para o clube %s, com os papéis: %s.
                Aceita o convite em: %s

                O link é válido durante 7 dias e só pode ser usado uma vez.
                Se não esperavas este convite, ignora esta mensagem.
                """.formatted(name.trim(), tenant.name(), String.join(", ", roles), link);
        emailSender.send(mail, "Convite para o clube " + tenant.name(), body);
    }

    /**
     * Quem ainda não tem conta define o nome e a password; quem já tem (por exemplo, noutro clube) só precisa
     * do token, que prova que controla o email. O token único e de curta duração é a prova.
     */
    @Transactional
    void acceptInvitation(String rawToken, String name, String password) {
        UUID tenantId = AuthService.requireTenant();

        AccountRepository.Invitation invitation = accounts
                .findInvitation(tenantId, OpaqueTokens.hash(rawToken))
                .filter(i -> !i.accepted() && !i.revoked() && i.expiresAt().isAfter(Instant.now()))
                .orElseThrow(AccountService::invalidLink);

        Optional<AccountRepository.UserBasic> existing = accounts.findUserByEmail(invitation.email());
        UUID userId;
        if (existing.isPresent()) {
            if (!"ACTIVE".equals(existing.get().status())) {
                throw invalidLink();
            }
            userId = existing.get().id();
        } else {
            requireValidPassword(password, invitation.email());
            String displayName = name == null || name.isBlank() ? invitation.name() : name.trim();
            userId = accounts.insertUserIfAbsent(invitation.email(), displayName, passwordEncoder.encode(password))
                    .orElseThrow(AccountService::invalidLink);
        }

        if (!accounts.markInvitationAccepted(tenantId, invitation.id())) {
            throw invalidLink();
        }
        Arrays.stream(invitation.roles().split(","))
                .forEach(role -> accounts.upsertMembership(tenantId, userId, role, invitation.invitedBy()));
        auth.audit(tenantId, userId, "INVITATION_ACCEPTED");
    }

    // --- recuperação de password ---

    /**
     * Responde sempre da mesma forma, exista ou não a conta, para não revelar quem tem conta.
     * Limita a 3 pedidos por hora e utilizador, para não se usar o endpoint para enviar spam.
     */
    @Transactional
    void forgotPassword(String email) {
        UUID tenantId = AuthService.requireTenant();
        String mail = normalizeEmail(email);

        Optional<AuthRepository.UserRow> user = auth.findActiveUserByEmail(mail);
        if (user.isEmpty() || auth.findActiveRoles(tenantId, user.get().id()).isEmpty()) {
            return;
        }
        UUID userId = user.get().id();
        if (accounts.countResetTokensSince(tenantId, userId, Instant.now().minus(Duration.ofHours(1)))
                >= MAX_RESETS_PER_HOUR) {
            return;
        }

        String rawToken = OpaqueTokens.newToken();
        accounts.insertResetToken(tenantId, userId, OpaqueTokens.hash(rawToken), Instant.now().plus(RESET_TTL));
        auth.audit(tenantId, userId, "PASSWORD_RESET_REQUESTED");

        AccountRepository.TenantInfo tenant = accounts.findTenant(tenantId).orElseThrow();
        String body = """
                Recebemos um pedido para repor a password da tua conta no clube %s.
                Define uma nova password em: %s

                O link é válido durante 1 hora e só pode ser usado uma vez.
                Se não pediste isto, ignora esta mensagem: a tua password não foi alterada.
                """.formatted(tenant.name(), link(tenant.slug(), "/reset-password", rawToken));
        emailSender.send(mail, "Repor a password (" + tenant.name() + ")", body);
    }

    @Transactional
    void resetPassword(String rawToken, String newPassword) {
        UUID tenantId = AuthService.requireTenant();

        AccountRepository.ResetToken token = accounts
                .findResetToken(tenantId, OpaqueTokens.hash(rawToken))
                .filter(t -> !t.used() && t.expiresAt().isAfter(Instant.now()))
                .orElseThrow(AccountService::invalidLink);
        requireValidPassword(newPassword, null);

        if (!accounts.markResetTokenUsed(tenantId, token.id())
                || !accounts.setPassword(token.userId(), passwordEncoder.encode(newPassword))) {
            throw invalidLink();
        }
        accounts.invalidateResetTokens(tenantId, token.userId());
        auth.revokeAllForUser(tenantId, token.userId());
        auth.audit(tenantId, token.userId(), "PASSWORD_RESET");
    }

    /** Devolve false se a password atual estiver errada (o controlador responde 400, e a tentativa fica auditada). */
    @Transactional
    boolean changePassword(UUID userId, String currentPassword, String newPassword) {
        UUID tenantId = AuthService.requireTenant();

        String hash = accounts.findPasswordHash(userId);
        boolean currentOk = !hash.isBlank() && PasswordPolicy.fitsBcrypt(currentPassword)
                && passwordEncoder.matches(currentPassword, hash);
        if (!currentOk) {
            auth.audit(tenantId, userId, "PASSWORD_CHANGE_FAILED");
            return false;
        }
        requireValidPassword(newPassword, null);
        if (newPassword.equals(currentPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nova password tem de ser diferente");
        }

        if (!accounts.setPassword(userId, passwordEncoder.encode(newPassword))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        auth.revokeAllForUser(tenantId, userId);
        auth.audit(tenantId, userId, "PASSWORD_CHANGED");
        return true;
    }

    // --- gestão de membros ---

    record MemberView(UUID userId, String email, String name, String status, java.util.List<AccountRepository.MemberRole> roles) {
    }

    @Transactional(readOnly = true)
    java.util.List<MemberView> listMembers() {
        UUID tenantId = AuthService.requireTenant();
        return accounts.listMembers(tenantId).stream()
                .map(m -> new MemberView(m.userId(), m.email(), m.name(), m.userStatus(),
                        accounts.listRoles(tenantId, m.userId())))
                .toList();
    }

    @Transactional
    void disableMember(UUID actorId, Set<String> actorRoles, UUID targetUserId) {
        UUID tenantId = AuthService.requireTenant();
        requireManager(actorRoles);
        if (actorId.equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não podes desativar a tua própria conta");
        }
        Set<String> targetRoles = accounts.findRoles(tenantId, targetUserId);
        if (targetRoles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!actorRoles.contains("ADMIN") && targetRoles.stream().anyMatch(PRIVILEGED::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só um administrador gere membros com este papel");
        }
        if (accounts.hasActiveRole(tenantId, targetUserId, "ADMIN") && accounts.countActiveAdmins(tenantId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O clube tem de manter pelo menos um administrador ativo");
        }
        accounts.disableAllMemberships(tenantId, targetUserId);
        auth.revokeAllForUser(tenantId, targetUserId);
        auth.audit(tenantId, actorId, "MEMBER_DISABLED");
    }

    @Transactional
    void removeRole(UUID actorId, Set<String> actorRoles, UUID targetUserId, String role) {
        UUID tenantId = AuthService.requireTenant();
        requireManager(actorRoles);
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Papel inválido");
        }
        if (actorId.equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não podes retirar o teu próprio papel");
        }
        if (!actorRoles.contains("ADMIN") && PRIVILEGED.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só um administrador retira este papel");
        }
        Set<String> targetRoles = accounts.findRoles(tenantId, targetUserId);
        if (targetRoles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!actorRoles.contains("ADMIN") && targetRoles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só um administrador gere membros com este papel");
        }
        if ("ADMIN".equals(normalized) && accounts.hasActiveRole(tenantId, targetUserId, "ADMIN")
                && accounts.countActiveAdmins(tenantId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O clube tem de manter pelo menos um administrador ativo");
        }
        if (!accounts.removeRole(tenantId, targetUserId, normalized)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (auth.findActiveRoles(tenantId, targetUserId).isEmpty()) {
            auth.revokeAllForUser(tenantId, targetUserId);
        }
        auth.audit(tenantId, actorId, "MEMBER_ROLE_REMOVED");
    }

    private static void requireManager(Set<String> actorRoles) {
        if (actorRoles.stream().noneMatch(PRIVILEGED::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    // --- auxiliares ---

    private static void requireValidPassword(String password, String email) {
        String problem = PasswordPolicy.check(password, email);
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        }
    }

    private static ResponseStatusException invalidLink() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link inválido ou expirado");
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String link(String slug, String path, String rawToken) {
        return webUrlTemplate.replace("{slug}", slug) + path + "?token=" + rawToken;
    }

    // (envio de e-mail delegado ao EmailSender/outbox)
}
