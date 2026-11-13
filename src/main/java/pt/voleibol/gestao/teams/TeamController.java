package pt.voleibol.gestao.teams;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

/**
 * Base mínima de equipas: CRUD e associação de staff/atletas. Escrita só para ADMIN/DIRECAO;
 * TREINADOR vê apenas as equipas em que é staff; os restantes papéis não têm acesso.
 */
@RestController
@RequestMapping("/api/v1/teams")
class TeamController {

    private static final String STATUS_PATTERN = "(?i)ACTIVE|INACTIVE";
    private static final String STAFF_ROLE_PATTERN = "(?i)TREINADOR|TREINADOR_ADJUNTO";

    record TeamRequest(@NotBlank @Size(max = 120) String name,
                       @Pattern(regexp = STATUS_PATTERN) String status,
                       UUID seasonId,
                       UUID ageGroupId) {
    }

    record StaffRequest(@NotNull UUID userId,
                        @Pattern(regexp = STAFF_ROLE_PATTERN) String role) {
    }

    record RosterRequest(@NotNull UUID athleteId) {
    }

    record TeamResponse(UUID id, String name, String status, UUID seasonId, UUID ageGroupId,
                        Instant createdAt, Instant updatedAt) {
        static TeamResponse from(TeamService.TeamView t) {
            return new TeamResponse(t.id(), t.name(), t.status(), t.seasonId(), t.ageGroupId(),
                    t.createdAt(), t.updatedAt());
        }
    }

    record StaffResponse(UUID userId, String name, String email, String role) {
        static StaffResponse from(TeamService.StaffView s) {
            return new StaffResponse(s.userId(), s.name(), s.email(), s.role());
        }
    }

    record RosterResponse(UUID athleteId, String fullName, String status) {
        static RosterResponse from(TeamService.RosterView a) {
            return new RosterResponse(a.athleteId(), a.fullName(), a.status());
        }
    }

    private final TeamService teamService;

    TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    List<TeamResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return teamService.list(actor(jwt), roles(jwt)).stream().map(TeamResponse::from).toList();
    }

    @GetMapping("/{teamId}")
    TeamResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {
        return TeamResponse.from(teamService.get(actor(jwt), roles(jwt), teamId));
    }

    @PostMapping
    ResponseEntity<TeamResponse> create(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody TeamRequest request) {
        TeamResponse created = TeamResponse.from(
                teamService.create(actor(jwt), roles(jwt), request.name(), request.status(),
                        request.seasonId(), request.ageGroupId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{teamId}")
    TeamResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                        @Valid @RequestBody TeamRequest request) {
        return TeamResponse.from(
                teamService.update(actor(jwt), roles(jwt), teamId, request.name(), request.status(),
                        request.seasonId(), request.ageGroupId()));
    }

    @DeleteMapping("/{teamId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {
        teamService.delete(actor(jwt), roles(jwt), teamId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/staff")
    List<StaffResponse> listStaff(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {
        return teamService.listStaff(actor(jwt), roles(jwt), teamId).stream()
                .map(StaffResponse::from).toList();
    }

    @PostMapping("/{teamId}/staff")
    List<StaffResponse> addStaff(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                                 @Valid @RequestBody StaffRequest request) {
        return teamService.addStaff(actor(jwt), roles(jwt), teamId, request.userId(), request.role())
                .stream().map(StaffResponse::from).toList();
    }

    @DeleteMapping("/{teamId}/staff/{userId}")
    ResponseEntity<Void> removeStaff(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                                     @PathVariable UUID userId) {
        teamService.removeStaff(actor(jwt), roles(jwt), teamId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/athletes")
    List<RosterResponse> listAthletes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {
        return teamService.listAthletes(actor(jwt), roles(jwt), teamId).stream()
                .map(RosterResponse::from).toList();
    }

    @PostMapping("/{teamId}/athletes")
    List<RosterResponse> addAthleteToTeam(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                                         @Valid @RequestBody RosterRequest request) {
        return teamService.addAthleteToTeam(actor(jwt), roles(jwt), teamId, request.athleteId()).stream()
                .map(RosterResponse::from).toList();
    }

    @DeleteMapping("/{teamId}/athletes/{athleteId}")
    ResponseEntity<Void> removeAthlete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                                       @PathVariable UUID athleteId) {
        teamService.removeAthlete(actor(jwt), roles(jwt), teamId, athleteId);
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
