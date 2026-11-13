package pt.voleibol.gestao.teams;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Escalões do clube (FPV): CRUD. A leitura é aberta a qualquer membro autenticado; a escrita é
 * exclusiva de ADMIN/DIRECAO, validada no serviço.
 */
@RestController
@RequestMapping("/api/v1/age-groups")
class AgeGroupController {

    record AgeGroupRequest(@NotBlank @Size(max = 120) String name,
                            @NotNull UUID seasonId,
                            @NotNull Integer minBirthYear,
                            @NotNull Integer maxBirthYear) {
    }

    record AgeGroupResponse(UUID id, UUID seasonId, String name, int minBirthYear, int maxBirthYear,
                             Instant createdAt, Instant updatedAt) {
        static AgeGroupResponse from(AgeGroupService.AgeGroupView g) {
            return new AgeGroupResponse(g.id(), g.seasonId(), g.name(), g.minBirthYear(), g.maxBirthYear(),
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

    @PostMapping
    ResponseEntity<AgeGroupResponse> create(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody AgeGroupRequest request) {
        AgeGroupResponse created = AgeGroupResponse.from(ageGroupService.create(actor(jwt), roles(jwt),
                request.seasonId(), request.name(), request.minBirthYear(), request.maxBirthYear()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{ageGroupId}")
    AgeGroupResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ageGroupId,
                             @Valid @RequestBody AgeGroupRequest request) {
        return AgeGroupResponse.from(ageGroupService.update(actor(jwt), roles(jwt), ageGroupId, request.seasonId(),
                request.name(), request.minBirthYear(), request.maxBirthYear()));
    }

    @DeleteMapping("/{ageGroupId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ageGroupId) {
        ageGroupService.delete(actor(jwt), roles(jwt), ageGroupId);
        return ResponseEntity.noContent().build();
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static Set<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Set.of() : Set.copyOf(roles);
    }
}
