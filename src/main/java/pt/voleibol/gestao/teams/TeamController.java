package pt.voleibol.gestao.teams;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
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

import pt.voleibol.gestao.shared.Auth;

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

    record RosterRequest(@NotNull UUID athleteId, UUID registrationAgeGroupId) {
    }

    private final TeamService teamService;

    TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    List<TeamService.TeamView> list(@AuthenticationPrincipal Jwt jwt) {
        return teamService.list(Auth.actor(jwt), Auth.roles(jwt));
    }

    @GetMapping("/{teamId}")
    TeamService.TeamView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {
        return teamService.get(Auth.actor(jwt), Auth.roles(jwt), teamId);
    }

    @PostMapping
    ResponseEntity<TeamService.TeamView> create(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody TeamRequest request) {
        TeamService.TeamView created = teamService.create(Auth.actor(jwt), Auth.roles(jwt), request.name(),
                request.status(), request.seasonId(), request.ageGroupId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{teamId}")
    TeamService.TeamView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                                @Valid @RequestBody TeamRequest request) {
        return teamService.update(Auth.actor(jwt), Auth.roles(jwt), teamId, request.name(), request.status(),
                request.seasonId(), request.ageGroupId());
    }

    @DeleteMapping("/{teamId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {
        teamService.delete(Auth.actor(jwt), Auth.roles(jwt), teamId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/staff")
    List<TeamService.StaffView> listStaff(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {
        return teamService.listStaff(Auth.actor(jwt), Auth.roles(jwt), teamId);
    }

    @PostMapping("/{teamId}/staff")
    List<TeamService.StaffView> addStaff(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                                         @Valid @RequestBody StaffRequest request) {
        return teamService.addStaff(Auth.actor(jwt), Auth.roles(jwt), teamId, request.userId(), request.role());
    }

    @DeleteMapping("/{teamId}/staff/{userId}")
    ResponseEntity<Void> removeStaff(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                                     @PathVariable UUID userId) {
        teamService.removeStaff(Auth.actor(jwt), Auth.roles(jwt), teamId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/athletes")
    List<TeamService.RosterView> listAthletes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {
        return teamService.listAthletes(Auth.actor(jwt), Auth.roles(jwt), teamId);
    }

    @PostMapping("/{teamId}/athletes")
    List<TeamService.RosterView> addAthleteToTeam(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                                                  @Valid @RequestBody RosterRequest request) {
        return teamService.addAthleteToTeam(Auth.actor(jwt), Auth.roles(jwt), teamId, request.athleteId(),
                request.registrationAgeGroupId());
    }

    @DeleteMapping("/{teamId}/athletes/{athleteId}")
    ResponseEntity<Void> removeAthlete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
                                       @PathVariable UUID athleteId) {
        teamService.removeAthlete(Auth.actor(jwt), Auth.roles(jwt), teamId, athleteId);
        return ResponseEntity.noContent().build();
    }
}
