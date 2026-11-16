package pt.voleibol.gestao.athletes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.multipart.MultipartFile;

import pt.voleibol.gestao.shared.CurrentUser;

/**
 * Atletas e encarregados de educação. A autorização fina é feita no serviço, porque depende da
 * relação do utilizador autenticado com cada atleta e não apenas do papel no clube.
 */
@RestController
@RequestMapping("/api/v1/athletes")
class AthleteController {

    private static final String STATUS_PATTERN = "(?i)ACTIVE|INACTIVE";
    private static final String GENDER_PATTERN = "(?i)F|M";
    private static final String NIF_PATTERN = "^$|\\d{9}";

    record CreateAthleteRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotNull @Past LocalDate birthDate,
            @NotBlank @Pattern(regexp = GENDER_PATTERN) String gender,
            @Pattern(regexp = NIF_PATTERN) String nif,
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
            @Pattern(regexp = NIF_PATTERN) String nif,
            @Email @Size(max = 254) String email,
            @Size(max = 30) String phone,
            @Size(max = 50) String licenseNumber) {
    }

    record StatusRequest(@NotBlank @Pattern(regexp = STATUS_PATTERN) String status) {
    }

    record WithdrawalRequest(@NotNull LocalDate withdrawalDate) {
    }

    record LinkAccountRequest(@NotNull UUID userId) {
    }

    record GuardianRequest(@NotNull UUID userId, @Size(max = 40) String relationship) {
    }

    private final AthleteService athleteService;
    private final AthleteStatsService statsService;

    AthleteController(AthleteService athleteService, AthleteStatsService statsService) {
        this.athleteService = athleteService;
        this.statsService = statsService;
    }

    @GetMapping
    AthleteService.Page list(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) UUID teamId,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return athleteService.list(CurrentUser.of(jwt), q, status, teamId, page, size);
    }

    @GetMapping("/{athleteId}")
    AthleteService.AthleteView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        return athleteService.get(CurrentUser.of(jwt), athleteId);
    }

    @GetMapping("/{athleteId}/stats")
    AthleteStatsService.Stats stats(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        return statsService.get(CurrentUser.of(jwt), athleteId);
    }

    @PostMapping
    ResponseEntity<AthleteService.AthleteView> create(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody CreateAthleteRequest request) {
        AthleteService.AthleteView created = athleteService.create(CurrentUser.of(jwt),
                AthleteService.data(request.fullName(), request.birthDate(), request.gender(), request.nif(),
                        request.email(), request.phone(), request.licenseNumber(), request.status(),
                        request.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{athleteId}")
    AthleteService.AthleteView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                                      @Valid @RequestBody UpdateAthleteRequest request) {
        return athleteService.update(CurrentUser.of(jwt), athleteId,
                AthleteService.data(request.fullName(), request.birthDate(), request.gender(), request.nif(),
                        request.email(), request.phone(), request.licenseNumber(), null, null));
    }

    @DeleteMapping("/{athleteId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        athleteService.delete(CurrentUser.of(jwt), athleteId);
        return ResponseEntity.noContent().build();
    }

    // --- conta e estado (só ADMIN/DIRECAO) ---

    @PostMapping("/{athleteId}/account")
    AthleteService.AthleteView linkAccount(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                                           @Valid @RequestBody LinkAccountRequest request) {
        return athleteService.linkAccount(CurrentUser.of(jwt), athleteId, request.userId());
    }

    @DeleteMapping("/{athleteId}/account")
    AthleteService.AthleteView unlinkAccount(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        return athleteService.unlinkAccount(CurrentUser.of(jwt), athleteId);
    }

    @PatchMapping("/{athleteId}/status")
    AthleteService.AthleteView changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                                            @Valid @RequestBody StatusRequest request) {
        return athleteService.changeStatus(CurrentUser.of(jwt), athleteId, request.status());
    }

    @PostMapping("/{athleteId}/withdrawal")
    AthleteService.AthleteView registerWithdrawal(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID athleteId,
                                                  @Valid @RequestBody WithdrawalRequest request) {
        return athleteService.registerWithdrawal(CurrentUser.of(jwt), athleteId,
                request.withdrawalDate());
    }

    // --- encarregados de educação ---

    @GetMapping("/{athleteId}/guardians")
    List<AthleteService.GuardianView> listGuardians(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable UUID athleteId) {
        return athleteService.listGuardians(CurrentUser.of(jwt), athleteId);
    }

    @PostMapping("/{athleteId}/guardians")
    List<AthleteService.GuardianView> addGuardian(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID athleteId,
                                                  @Valid @RequestBody GuardianRequest request) {
        return athleteService.addGuardian(CurrentUser.of(jwt), athleteId, request.userId(),
                request.relationship());
    }

    @DeleteMapping("/{athleteId}/guardians/{userId}")
    ResponseEntity<Void> removeGuardian(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                                        @PathVariable UUID userId) {
        athleteService.removeGuardian(CurrentUser.of(jwt), athleteId, userId);
        return ResponseEntity.noContent().build();
    }

    // --- importação CSV ---
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    ResponseEntity<Map<String, Object>> importCsv(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestParam("file") MultipartFile file) throws java.io.IOException {
        int count = athleteService.importCsv(CurrentUser.of(jwt),
                new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        return ResponseEntity.ok(Map.of("imported", count));
    }
}
