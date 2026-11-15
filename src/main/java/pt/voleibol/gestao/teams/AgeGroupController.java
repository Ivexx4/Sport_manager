package pt.voleibol.gestao.teams;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pt.voleibol.gestao.shared.Auth;

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
    List<AgeGroupService.AgeGroupView> list(@AuthenticationPrincipal Jwt jwt) {
        return ageGroupService.list(Auth.actor(jwt), Auth.roles(jwt));
    }

    @GetMapping("/{ageGroupId}")
    AgeGroupService.AgeGroupView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ageGroupId) {
        return ageGroupService.get(Auth.actor(jwt), Auth.roles(jwt), ageGroupId);
    }
}
