package pt.voleibol.gestao.athletes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Atletas e encarregados de educação. A autorização fina é feita no serviço, porque depende da
 * relação do utilizador autenticado com cada atleta e não apenas do papel no clube.
 */
@RestController
@RequestMapping("/api/v1/athletes")
class AthleteController {

    private static final String STATUS_PATTERN = "(?i)ACTIVE|INACTIVE";
    private static final String GENDER_PATTERN = "(?i)F|M";

    record CreateAthleteRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotNull @Past LocalDate birthDate,
            @NotBlank @Pattern(regexp = GENDER_PATTERN) String gender,
            @Pattern(regexp = "^$|\\d{9}") String nif,
            @Email @Size(max = 254) String email,
            @Size(max = 30) String phone,
            @Size(max = 50) String licenseNumber,
            UUID userId,
            @Pattern(regexp = STATUS_PATTERN) String status) {
    }

    record UpdateAthleteRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotNull @Past LocalDate birthDate,
            @NotBlank @Pattern(regexp = GENDER_PATTERN) String gender,
            @Pattern(regexp = "^$|\\d{9}") String nif,
            @Email @Size(max = 254) String email,
            @Size(max = 30) String phone,
            @Size(max = 50) String licenseNumber) {
    }

    record StatusRequest(@NotBlank @Pattern(regexp = STATUS_PATTERN) String status) {
    }

    record LinkAccountRequest(@NotNull UUID userId) {
    }

    record GuardianRequest(@NotNull UUID userId, @Size(max = 40) String relationship) {
    }

    record AthleteResponse(UUID id, String fullName, LocalDate birthDate, String gender, String nif,
                           String email, String phone, String licenseNumber, String status, UUID userId,
                           Instant createdAt, Instant updatedAt) {
        static AthleteResponse from(AthleteService.AthleteView v) {
            return new AthleteResponse(v.id(), v.fullName(), v.birthDate(), v.gender(), v.nif(),
                    v.email(), v.phone(), v.licenseNumber(), v.status(), v.userId(),
                    v.createdAt(), v.updatedAt());
        }
    }

    record PageResponse(List<AthleteResponse> items, int page, int size, long total) {
        static PageResponse from(AthleteService.Page page) {
            return new PageResponse(page.items().stream().map(AthleteResponse::from).toList(),
                    page.page(), page.size(), page.total());
        }
    }

    record GuardianResponse(UUID userId, String name, String email, String relationship) {
        static GuardianResponse from(AthleteService.GuardianView g) {
            return new GuardianResponse(g.userId(), g.name(), g.email(), g.relationship());
        }
    }

    private final AthleteService athleteService;

    AthleteController(AthleteService athleteService) {
        this.athleteService = athleteService;
    }

    @GetMapping
    PageResponse list(@AuthenticationPrincipal Jwt jwt,
                      @RequestParam(required = false) String q,
                      @RequestParam(required = false) String status,
                      @RequestParam(required = false) UUID teamId,
                      @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(athleteService.list(actor(jwt), roles(jwt), q, status, teamId, page, size));
    }

    @GetMapping("/{athleteId}")
    AthleteResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        return AthleteResponse.from(athleteService.get(actor(jwt), roles(jwt), athleteId));
    }

    @PostMapping
    ResponseEntity<AthleteResponse> create(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody CreateAthleteRequest request) {
        AthleteService.AthleteView created = athleteService.create(actor(jwt), roles(jwt),
                AthleteService.data(request.fullName(), request.birthDate(), request.gender(), request.nif(),
                        request.email(), request.phone(), request.licenseNumber(), request.status(),
                        request.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AthleteResponse.from(created));
    }

    @PutMapping("/{athleteId}")
    AthleteResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                           @Valid @RequestBody UpdateAthleteRequest request) {
        return AthleteResponse.from(athleteService.update(actor(jwt), roles(jwt), athleteId,
                AthleteService.data(request.fullName(), request.birthDate(), request.gender(), request.nif(),
                        request.email(), request.phone(), request.licenseNumber(), null, null)));
    }

    @DeleteMapping("/{athleteId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        athleteService.delete(actor(jwt), roles(jwt), athleteId);
        return ResponseEntity.noContent().build();
    }

    // --- conta e estado (só ADMIN/DIRECAO) ---

    @PostMapping("/{athleteId}/account")
    AthleteResponse linkAccount(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                                @Valid @RequestBody LinkAccountRequest request) {
        return AthleteResponse.from(
                athleteService.linkAccount(actor(jwt), roles(jwt), athleteId, request.userId()));
    }

    @DeleteMapping("/{athleteId}/account")
    AthleteResponse unlinkAccount(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        return AthleteResponse.from(athleteService.unlinkAccount(actor(jwt), roles(jwt), athleteId));
    }

    @PatchMapping("/{athleteId}/status")
    AthleteResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                                 @Valid @RequestBody StatusRequest request) {
        return AthleteResponse.from(
                athleteService.changeStatus(actor(jwt), roles(jwt), athleteId, request.status()));
    }

    // --- encarregados de educação ---

    @GetMapping("/{athleteId}/guardians")
    List<GuardianResponse> listGuardians(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        return athleteService.listGuardians(actor(jwt), roles(jwt), athleteId).stream()
                .map(GuardianResponse::from).toList();
    }

    @PostMapping("/{athleteId}/guardians")
    List<GuardianResponse> addGuardian(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                                       @Valid @RequestBody GuardianRequest request) {
        return athleteService.addGuardian(actor(jwt), roles(jwt), athleteId, request.userId(),
                        request.relationship()).stream()
                .map(GuardianResponse::from).toList();
    }

    @DeleteMapping("/{athleteId}/guardians/{userId}")
    ResponseEntity<Void> removeGuardian(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                                        @PathVariable UUID userId) {
        athleteService.removeGuardian(actor(jwt), roles(jwt), athleteId, userId);
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
