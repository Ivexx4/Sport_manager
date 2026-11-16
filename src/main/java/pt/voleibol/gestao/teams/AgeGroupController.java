package pt.voleibol.gestao.teams;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pt.voleibol.gestao.shared.CurrentUser;

/**
 * Catálogo federal FPV somente de leitura para os clubes.
 */
@RestController
@RequestMapping("/api/v1/age-groups")
class AgeGroupController {

    private final AgeGroupService ageGroupService;

    AgeGroupController(AgeGroupService ageGroupService) {
        this.ageGroupService = ageGroupService;
    }

    @GetMapping
    List<AgeGroupService.AgeGroupView> list(CurrentUser user) {
        return ageGroupService.list(user);
    }

    @GetMapping("/{ageGroupId}")
    AgeGroupService.AgeGroupView get(CurrentUser user, @PathVariable UUID ageGroupId) {
        return ageGroupService.get(user, ageGroupId);
    }
}
