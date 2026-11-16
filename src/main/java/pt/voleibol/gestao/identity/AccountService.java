package pt.voleibol.gestao.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Role;
import pt.voleibol.gestao.shared.Rules;

import pt.voleibol.gestao.notifications.EmailSender;

/**
 * Convites de utilizadores, aceitação, recuperação e alteração de password, sempre no contexto de um clube.
 *
 * <p>Os e-mails são enfileirados na outbox na mesma transação: se a operação falhar, nada é enviado.
 */
@Service
class AccountService {

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
    void invite(CurrentUser inviter, String email, String name, Set<String> requestedRoles) {
        UUID tenantId = Rules.tenant();

        Rules.requireManager(inviter);
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String requested : requestedRoles) {
            roles.add(Role.from(requested).orElseThrow(() -> Rules.badRequest("Papel inválido")));
        }
        if (roles.isEmpty()) throw Rules.badRequest("Papel inválido");
        if (!inviter.has(Role.ADMIN) && roles.stream().anyMatch(Role.MANAGERS::contains))
            throw Rules.forbidden("Só um administrador atribui este papel");

        List<String> codes = List.copyOf(new TreeSet<>(Role.codes(roles)));
        String mail = normalizeEmail(email);
        accounts.revokePendingInvitations(tenantId, mail);   // um convite pendente por email
        String rawToken = OpaqueTokens.newToken();
        accounts.insertInvitation(tenantId, mail, name.trim(), String.join(",", codes),
                OpaqueTokens.hash(rawToken), inviter.id(), Instant.now().plus(INVITATION_TTL));
        auth.audit(tenantId, inviter.id(), "USER_INVITED");

        AccountRepository.TenantInfo tenant = accounts.findTenant(tenantId).orElseThrow();
        String link = link(tenant.slug(), "/accept-invitation", rawToken);
        String body = """
                Olá %s,

                Foste convidado(a) para o clube %s, com os papéis: %s.
                Aceita o convite em: %s

                O link é válido durante 7 dias e só pode ser usado uma vez.
                Se não esperavas este convite, ignora esta mensagem.
                """.formatted(name.trim(), tenant.name(), String.join(", ", codes), link);
        emailSender.send(mail, "Convite para o clube " + tenant.name(), body);
    }

    /**
     * Quem ainda não tem conta define o nome e a password; quem já tem (por exemplo, noutro clube) só precisa
     * do token, que prova que controla o email. O token único e de curta duração é a prova.
     */
    @Transactional
    void acceptInvitation(String rawToken, String name, String password) {
        UUID tenantId = Rules.tenant();

        AccountRepository.Invitation invitation = accounts
                .findInvitation(tenantId, OpaqueTokens.hash(rawToken))
                .filter(i -> !i.accepted() && !i.revoked() && i.expiresAt().isAfter(Instant.now()))
                .orElseThrow(AccountService::invalidLink);

        Optional<AccountRepository.UserBasic> existing = accounts.findUserByEmail(invitation.email());
        UUID userId;
        if (existing.isPresent()) {
            if (!"ACTIVE".equals(existing.get().status())) throw invalidLink();
            userId = existing.get().id();
        } else {
            requireValidPassword(password, invitation.email());
            String displayName = name == null || name.isBlank() ? invitation.name() : name.trim();
            userId = accounts.insertUserIfAbsent(invitation.email(), displayName, passwordEncoder.encode(password))
                    .orElseThrow(AccountService::invalidLink);
        }

        if (!accounts.markInvitationAccepted(tenantId, invitation.id())) throw invalidLink();
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
        UUID tenantId = Rules.tenant();
        String mail = normalizeEmail(email);

        Optional<AuthRepository.UserRow> user = auth.findActiveUserByEmail(mail);
        if (user.isEmpty() || auth.findActiveRoles(tenantId, user.get().id()).isEmpty()) return;
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
        UUID tenantId = Rules.tenant();

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
        UUID tenantId = Rules.tenant();

        String hash = accounts.findPasswordHash(userId);
        boolean currentOk = !hash.isBlank() && PasswordPolicy.fitsBcrypt(currentPassword)
                && passwordEncoder.matches(currentPassword, hash);
        if (!currentOk) {
            auth.audit(tenantId, userId, "PASSWORD_CHANGE_FAILED");
            return false;
        }
        requireValidPassword(newPassword, null);
        if (newPassword.equals(currentPassword)) throw Rules.badRequest("A nova password tem de ser diferente");

        if (!accounts.setPassword(userId, passwordEncoder.encode(newPassword))) throw Rules.forbidden();
        auth.revokeAllForUser(tenantId, userId);
        auth.audit(tenantId, userId, "PASSWORD_CHANGED");
        return true;
    }

    // --- gestão de membros ---

    record MemberView(UUID userId, String email, String name, String status, java.util.List<AccountRepository.MemberRole> roles) {
    }

    @Transactional(readOnly = true)
    java.util.List<MemberView> listMembers() {
        UUID tenantId = Rules.tenant();
        return accounts.listMembers(tenantId).stream()
                .map(m -> new MemberView(m.userId(), m.email(), m.name(), m.userStatus(),
                        accounts.listRoles(tenantId, m.userId())))
                .toList();
    }

    @Transactional
    void disableMember(CurrentUser actor, UUID targetUserId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(actor);
        if (actor.id().equals(targetUserId)) throw Rules.badRequest("Não podes desativar a tua própria conta");
        Set<Role> targetRoles = Role.fromAll(accounts.findRoles(tenantId, targetUserId));
        if (targetRoles.isEmpty()) throw Rules.notFound();
        if (!actor.has(Role.ADMIN) && targetRoles.stream().anyMatch(Role.MANAGERS::contains))
            throw Rules.forbidden("Só um administrador gere membros com este papel");
        if (accounts.hasActiveRole(tenantId, targetUserId, Role.ADMIN.name())
                && accounts.countActiveAdmins(tenantId) <= 1)
            throw Rules.conflict("O clube tem de manter pelo menos um administrador ativo");
        accounts.disableAllMemberships(tenantId, targetUserId);
        auth.revokeAllForUser(tenantId, targetUserId);
        auth.audit(tenantId, actor.id(), "MEMBER_DISABLED");
    }

    @Transactional
    void removeRole(CurrentUser actor, UUID targetUserId, String role) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(actor);
        Role target = Role.from(role).orElseThrow(() -> Rules.badRequest("Papel inválido"));
        if (actor.id().equals(targetUserId)) throw Rules.badRequest("Não podes retirar o teu próprio papel");
        if (!actor.has(Role.ADMIN) && Role.MANAGERS.contains(target))
            throw Rules.forbidden("Só um administrador retira este papel");
        Set<Role> targetRoles = Role.fromAll(accounts.findRoles(tenantId, targetUserId));
        if (targetRoles.isEmpty()) throw Rules.notFound();
        if (!actor.has(Role.ADMIN) && targetRoles.contains(Role.ADMIN))
            throw Rules.forbidden("Só um administrador gere membros com este papel");
        if (target == Role.ADMIN && accounts.hasActiveRole(tenantId, targetUserId, Role.ADMIN.name())
                && accounts.countActiveAdmins(tenantId) <= 1) {
            throw Rules.conflict("O clube tem de manter pelo menos um administrador ativo");
        }
        if (!accounts.removeRole(tenantId, targetUserId, target.name())) throw Rules.notFound();
        if (auth.findActiveRoles(tenantId, targetUserId).isEmpty()) auth.revokeAllForUser(tenantId, targetUserId);
        auth.audit(tenantId, actor.id(), "MEMBER_ROLE_REMOVED");
    }

    // --- auxiliares ---

    private static void requireValidPassword(String password, String email) {
        String problem = PasswordPolicy.check(password, email);
        if (problem != null) throw Rules.badRequest(problem);
    }

    private static ResponseStatusException invalidLink() {
        return Rules.badRequest("Link inválido ou expirado");
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String link(String slug, String path, String rawToken) {
        return webUrlTemplate.replace("{slug}", slug) + path + "?token=" + rawToken;
    }

    // (envio de e-mail delegado ao EmailSender/outbox)
}
