package pt.voleibol.gestao.athletes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Atletas e encarregados de educação, com autorização por recurso:
 * <ul>
 *   <li>ADMIN e DIRECAO gerem todos os atletas do clube;</li>
 *   <li>TREINADOR lê e altera os atletas das equipas em que é staff;</li>
 *   <li>ATLETA lê e altera apenas o seu registo;</li>
 *   <li>ENCARREGADO lê e altera apenas os seus educandos;</li>
 *   <li>FINANCEIRO só lê.</li>
 * </ul>
 * Criar, apagar, ligar contas, mudar estado e gerir encarregados é exclusivo de ADMIN/DIRECAO.
 */
@Service
class AthleteService {

    private static final Set<String> MANAGERS = Set.of("ADMIN", "DIRECAO");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    record AthleteView(UUID id, String fullName, LocalDate birthDate, String gender, String nif,
                       String email, String phone, String licenseNumber, String status, UUID userId,
                       Instant createdAt, Instant updatedAt) {
    }

    record Page(List<AthleteView> items, int page, int size, long total) {
    }

    record GuardianView(UUID userId, String name, String email, String relationship) {
    }

    private final AthleteRepository athletes;

    AthleteService(AthleteRepository athletes) {
        this.athletes = athletes;
    }

    @Transactional(readOnly = true)
    Page list(UUID actorId, Set<String> roles, String query, String status, UUID teamId, int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Página inválida");
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tamanho da página entre 1 e 100");
        }
        UUID tenantId = requireTenant();
        String normalizedStatus = status == null || status.isBlank() ? null : validStatus(status);
        List<AthleteView> items = athletes
                .list(tenantId, actorId, roles, query, normalizedStatus, teamId, size, (long) page * size)
                .stream().map(AthleteService::view).toList();
        long total = athletes.count(tenantId, actorId, roles, query, normalizedStatus, teamId);
        return new Page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    AthleteView get(UUID actorId, Set<String> roles, UUID athleteId) {
        UUID tenantId = requireTenant();
        return athletes.findVisible(tenantId, athleteId, actorId, roles)
                .map(AthleteService::view)
                .orElseThrow(AthleteService::notFound);
    }

    @Transactional
    AthleteView create(UUID actorId, Set<String> roles, AthleteRepository.Data data) {
        UUID tenantId = requireTenant();
        requireManager(roles);

        if (data.licenseNumber() != null && athletes.licenseExists(tenantId, data.licenseNumber(), null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um atleta com esta licença");
        }
        if (data.userId() != null) {
            requireMembership(tenantId, data.userId());
            if (athletes.userLinked(tenantId, data.userId(), null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta conta já está ligada a um atleta");
            }
        }

        UUID id = UUID.randomUUID();
        try {
            athletes.insert(id, tenantId, data, actorId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um atleta com estes dados");
        }
        athletes.audit(tenantId, actorId, "ATHLETE_CREATED", id);
        return get(actorId, roles, id);
    }

    @Transactional
    AthleteView update(UUID actorId, Set<String> roles, UUID athleteId, AthleteRepository.Data data) {
        UUID tenantId = requireTenant();
        requireWritable(tenantId, athleteId, actorId, roles);

        if (data.licenseNumber() != null
                && athletes.licenseExists(tenantId, data.licenseNumber(), athleteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um atleta com esta licença");
        }
        try {
            if (!athletes.update(tenantId, athleteId, data)) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um atleta com esta licença");
        }
        athletes.audit(tenantId, actorId, "ATHLETE_UPDATED", athleteId);
        return get(actorId, roles, athleteId);
    }

    @Transactional
    void delete(UUID actorId, Set<String> roles, UUID athleteId) {
        UUID tenantId = requireTenant();
        if (!isManager(roles)) {
            // 404 se não o pode ver; 403 se o vê mas não o pode apagar.
            requireVisible(tenantId, athleteId, actorId, roles);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        requireVisible(tenantId, athleteId, actorId, roles);
        if (!athletes.delete(tenantId, athleteId)) {
            throw notFound();
        }
        athletes.audit(tenantId, actorId, "ATHLETE_DELETED", athleteId);
    }

    // --- conta e estado ---

    @Transactional
    AthleteView linkAccount(UUID actorId, Set<String> roles, UUID athleteId, UUID userId) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireVisible(tenantId, athleteId, actorId, roles);
        requireMembership(tenantId, userId);
        if (athletes.userLinked(tenantId, userId, athleteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta conta já está ligada a outro atleta");
        }
        try {
            if (!athletes.linkUser(tenantId, athleteId, userId)) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta conta já está ligada a outro atleta");
        }
        athletes.audit(tenantId, actorId, "ATHLETE_ACCOUNT_LINKED", athleteId);
        return get(actorId, roles, athleteId);
    }

    @Transactional
    AthleteView unlinkAccount(UUID actorId, Set<String> roles, UUID athleteId) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireVisible(tenantId, athleteId, actorId, roles);
        if (!athletes.unlinkUser(tenantId, athleteId)) {
            throw notFound();
        }
        athletes.audit(tenantId, actorId, "ATHLETE_ACCOUNT_UNLINKED", athleteId);
        return get(actorId, roles, athleteId);
    }

    @Transactional
    AthleteView changeStatus(UUID actorId, Set<String> roles, UUID athleteId, String status) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireVisible(tenantId, athleteId, actorId, roles);
        if (!athletes.updateStatus(tenantId, athleteId, validStatus(status))) {
            throw notFound();
        }
        athletes.audit(tenantId, actorId, "ATHLETE_STATUS_CHANGED", athleteId);
        return get(actorId, roles, athleteId);
    }

    // --- encarregados ---

    @Transactional(readOnly = true)
    List<GuardianView> listGuardians(UUID actorId, Set<String> roles, UUID athleteId) {
        UUID tenantId = requireTenant();
        requireVisible(tenantId, athleteId, actorId, roles);
        return athletes.listGuardians(tenantId, athleteId).stream()
                .map(g -> new GuardianView(g.userId(), g.name(), g.email(), g.relationship()))
                .toList();
    }

    @Transactional
    List<GuardianView> addGuardian(UUID actorId, Set<String> roles, UUID athleteId, UUID userId,
                                   String relationship) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireVisible(tenantId, athleteId, actorId, roles);
        requireMembership(tenantId, userId);
        if (athletes.guardianExists(tenantId, athleteId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este encarregado já está associado ao atleta");
        }
        try {
            athletes.insertGuardian(tenantId, athleteId, userId, trimToNull(relationship), actorId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este encarregado já está associado ao atleta");
        }
        athletes.audit(tenantId, actorId, "ATHLETE_GUARDIAN_ADDED", athleteId);
        return listGuardians(actorId, roles, athleteId);
    }

    @Transactional
    void removeGuardian(UUID actorId, Set<String> roles, UUID athleteId, UUID userId) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireVisible(tenantId, athleteId, actorId, roles);
        if (!athletes.deleteGuardian(tenantId, athleteId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Encarregado não associado ao atleta");
        }
        athletes.audit(tenantId, actorId, "ATHLETE_GUARDIAN_REMOVED", athleteId);
    }

    // --- auxiliares ---

    static AthleteRepository.Data data(String fullName, LocalDate birthDate, String gender, String nif,
                                       String email, String phone, String licenseNumber, String status,
                                       UUID userId) {
        return new AthleteRepository.Data(
                fullName.trim(),
                birthDate,
                gender.trim().toUpperCase(Locale.ROOT),
                trimToNull(nif),
                trimToNull(email) == null ? null : email.trim().toLowerCase(Locale.ROOT),
                trimToNull(phone),
                trimToNull(licenseNumber),
                validStatus(status == null ? "ACTIVE" : status),
                userId);
    }

    static String validStatus(String status) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado inválido");
        }
        return normalized;
    }

    private void requireVisible(UUID tenantId, UUID athleteId, UUID actorId, Set<String> roles) {
        athletes.findVisible(tenantId, athleteId, actorId, roles).orElseThrow(AthleteService::notFound);
    }

    private void requireWritable(UUID tenantId, UUID athleteId, UUID actorId, Set<String> roles) {
        Optional<AthleteRepository.AthleteRow> writable =
                athletes.findWritable(tenantId, athleteId, actorId, roles);
        if (writable.isPresent()) {
            return;
        }
        if (athletes.findVisible(tenantId, athleteId, actorId, roles).isPresent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        throw notFound();
    }

    private void requireMembership(UUID tenantId, UUID userId) {
        if (!athletes.activeMembershipExists(tenantId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilizador sem papel ativo no clube");
        }
    }

    private static void requireManager(Set<String> roles) {
        if (!isManager(roles)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static boolean isManager(Set<String> roles) {
        return roles.stream().anyMatch(MANAGERS::contains);
    }

    private static UUID requireTenant() {
        return TenantContext.current().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube: usa o subdomínio do clube"));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Atleta não encontrado");
    }

    private static AthleteView view(AthleteRepository.AthleteRow row) {
        return new AthleteView(row.id(), row.fullName(), row.birthDate(), row.gender(), row.nif(),
                row.email(), row.phone(), row.licenseNumber(), row.status(), row.userId(),
                row.createdAt(), row.updatedAt());
    }
}
