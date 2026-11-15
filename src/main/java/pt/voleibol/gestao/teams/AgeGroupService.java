package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Catálogo federal FPV. Os escalões são comuns a todos os clubes e não são configuráveis pelo clube.
 */
@Service
class AgeGroupService {

    record AgeGroupView(UUID id, String name, int minAge, Integer maxAge, boolean isActive,
                        Instant createdAt, Instant updatedAt) {
    }

    private final AgeGroupRepository ageGroups;
    AgeGroupService(AgeGroupRepository ageGroups, SeasonRepository seasons) {
        this.ageGroups = ageGroups;
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

    // O catálogo é mantido por migrações federativas, não por endpoints do clube.

    private static void requireMember(Set<String> roles) {
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static UUID requireTenant() {
        return TenantContext.current().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube: usa o subdomínio do clube"));
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Escalão não encontrado");
    }

    private static AgeGroupView view(AgeGroupRepository.AgeGroupRow row) {
        return new AgeGroupView(row.id(), row.name(), row.minAge(), row.maxAge(), row.isActive(),
                row.createdAt(), row.updatedAt());
    }
}
