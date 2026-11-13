package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Escalões do clube (FPV): faixas de anos de nascimento de uma época. Qualquer membro autenticado
 * lê; a criação, edição e remoção são exclusivas de ADMIN/DIRECAO.
 */
@Service
class AgeGroupService {

    private static final Set<String> MANAGERS = Set.of("ADMIN", "DIRECAO");

    record AgeGroupView(UUID id, UUID seasonId, String name, int minBirthYear, int maxBirthYear,
                        Instant createdAt, Instant updatedAt) {
    }

    private final AgeGroupRepository ageGroups;
    private final SeasonRepository seasons;

    AgeGroupService(AgeGroupRepository ageGroups, SeasonRepository seasons) {
        this.ageGroups = ageGroups;
        this.seasons = seasons;
    }
    @Transactional(readOnly = true)
    List<AgeGroupView> list(UUID actorId, Set<String> roles) {
        requireMember(roles);
        return ageGroups.list(requireTenant()).stream().map(AgeGroupService::view).toList();
    }

    @Transactional(readOnly = true)
    AgeGroupView get(UUID actorId, Set<String> roles, UUID ageGroupId) {
        requireMember(roles);
        return view(ageGroups.findById(requireTenant(), ageGroupId).orElseThrow(AgeGroupService::notFound));
    }

    @Transactional
    AgeGroupView create(UUID actorId, Set<String> roles, UUID seasonId, String name, int minBirthYear,
                        int maxBirthYear) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireSeason(tenantId, seasonId);
        String normalizedName = requiredText(name);
        requireValidBirthYears(minBirthYear, maxBirthYear);
        if (ageGroups.nameExists(tenantId, seasonId, normalizedName, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um escalão com este nome");
        }
        UUID id = UUID.randomUUID();
        try {
            ageGroups.insert(id, tenantId, new AgeGroupRepository.Data(seasonId, normalizedName, minBirthYear,
                    maxBirthYear), actorId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um escalão com este nome");
        }
        ageGroups.audit(tenantId, actorId, "AGE_GROUP_CREATED", id);
        return view(ageGroups.findById(tenantId, id).orElseThrow(AgeGroupService::notFound));
    }

    @Transactional
    AgeGroupView update(UUID actorId, Set<String> roles, UUID ageGroupId, UUID seasonId, String name,
                        int minBirthYear, int maxBirthYear) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        AgeGroupRepository.AgeGroupRow current =
                ageGroups.findById(tenantId, ageGroupId).orElseThrow(AgeGroupService::notFound);
        requireSeason(tenantId, seasonId);
        String normalizedName = requiredText(name);
        requireValidBirthYears(minBirthYear, maxBirthYear);
        if (ageGroups.nameExists(tenantId, seasonId, normalizedName, ageGroupId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um escalão com este nome");
        }
        try {
            if (!ageGroups.update(tenantId, ageGroupId, new AgeGroupRepository.Data(seasonId, normalizedName,
                    minBirthYear, maxBirthYear))) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um escalão com este nome");
        }
        ageGroups.audit(tenantId, actorId, "AGE_GROUP_UPDATED", ageGroupId);
        return view(ageGroups.findById(tenantId, ageGroupId).orElseThrow(AgeGroupService::notFound));
    }

    @Transactional
    void delete(UUID actorId, Set<String> roles, UUID ageGroupId) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        // Remoção não destrutiva: anonimiza o escalão, preservando o registo e as equipas associadas.
        if (!ageGroups.anonymize(tenantId, ageGroupId)) {
            throw notFound();
        }
        ageGroups.audit(tenantId, actorId, "AGE_GROUP_ANONYMIZED", ageGroupId);
    }

    // --- auxiliares ---

    private static void requireMember(Set<String> roles) {
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static void requireManager(Set<String> roles) {
        if (roles.stream().noneMatch(MANAGERS::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private void requireSeason(UUID tenantId, UUID seasonId) {
        if (!seasons.findById(tenantId, seasonId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Época não encontrada");
        }
    }

    private static void requireValidBirthYears(int minBirthYear, int maxBirthYear) {
        if (minBirthYear < 1900 || maxBirthYear < 1900) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Anos de nascimento inválidos");
        }
        if (maxBirthYear < minBirthYear) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O ano de nascimento máximo não pode ser inferior ao mínimo");
        }
    }

    private static String requiredText(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome obrigatório");
        }
        return value.trim();
    }

    private static UUID requireTenant() {
        return TenantContext.current().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube: usa o subdomínio do clube"));
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Escalão não encontrado");
    }

    private static AgeGroupView view(AgeGroupRepository.AgeGroupRow row) {
        return new AgeGroupView(row.id(), row.seasonId(), row.name(), row.minBirthYear(), row.maxBirthYear(),
                row.createdAt(), row.updatedAt());
    }
}
