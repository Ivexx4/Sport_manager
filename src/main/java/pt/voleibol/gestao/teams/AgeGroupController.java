package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * Catálogo federal FPV somente de leitura para os clubes.
 */
@RestController
@RequestMapping("/api/v1/age-groups")
class AgeGroupController {

    record AgeGroupResponse(UUID id, String name, int minAge, Integer maxAge, boolean isActive,
                             Instant createdAt, Instant updatedAt) {
        static AgeGroupResponse from(AgeGroupService.AgeGroupView g) {
            return new AgeGroupResponse(g.id(), g.name(), g.minAge(), g.maxAge(), g.isActive(),
                    g.createdAt(), g.updatedAt());
        }
    }

    private final AgeGroupService ageGroupService;

    AgeGroupController(AgeGroupService ageGroupService) {
        this.ageGroupService = ageGroupService;
    }

    @GetMapping
    List<AgeGroupResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return ageGroupService.list(actor(jwt), roles(jwt)).stream().map(AgeGroupResponse::from).toList();
    }

    @GetMapping("/{ageGroupId}")
    AgeGroupResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ageGroupId) {
        return AgeGroupResponse.from(ageGroupService.get(actor(jwt), roles(jwt), ageGroupId));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static Set<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Set.of() : Set.copyOf(roles);
    }
}
