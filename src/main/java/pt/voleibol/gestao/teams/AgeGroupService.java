package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Rules;

/**
 * Catálogo federal FPV. Os escalões são comuns a todos os clubes e não são configuráveis pelo clube.
 */
@Service
class AgeGroupService {

    record AgeGroupView(UUID id, String name, int minAge, Integer maxAge, boolean isActive,
                        Instant createdAt, Instant updatedAt) {
    }

    private final AgeGroupRepository ageGroups;

    AgeGroupService(AgeGroupRepository ageGroups) {
        this.ageGroups = ageGroups;
    }

    @Transactional(readOnly = true)
    List<AgeGroupView> list(CurrentUser user) {
        Rules.requireMember(user);
        return ageGroups.list(Rules.tenant()).stream().map(AgeGroupService::view).toList();
    }

    @Transactional(readOnly = true)
    AgeGroupView get(CurrentUser user, UUID ageGroupId) {
        Rules.requireMember(user);
        return view(ageGroups.findById(Rules.tenant(), ageGroupId).orElseThrow(AgeGroupService::notFound));
    }

    // O catálogo é mantido por migrações federativas, não por endpoints do clube.

    static ResponseStatusException notFound() {
        return Rules.notFound("Escalão não encontrado");
    }

    private static AgeGroupView view(AgeGroupRepository.AgeGroupRow row) {
        return new AgeGroupView(row.id(), row.name(), row.minAge(), row.maxAge(), row.isActive(),
                row.createdAt(), row.updatedAt());
    }
}
