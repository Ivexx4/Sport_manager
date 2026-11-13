package pt.voleibol.gestao.teams;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Épocas do clube: CRUD. A leitura é aberta a qualquer membro autenticado; a escrita é exclusiva
 * de ADMIN/DIRECAO, validada no serviço.
 */
@RestController
@RequestMapping("/api/v1/seasons")
class SeasonController {

    record SeasonRequest(@NotBlank @Size(max = 120) String name,
                          LocalDate startDate,
                          LocalDate endDate,
                          Boolean isActive) {
    }

    record SeasonResponse(UUID id, String name, LocalDate startDate, LocalDate endDate, boolean isActive,
                           Instant createdAt, Instant updatedAt) {
        static SeasonResponse from(SeasonService.SeasonView s) {
            return new SeasonResponse(s.id(), s.name(), s.startDate(), s.endDate(), s.isActive(),
                    s.createdAt(), s.updatedAt());
        }
    }

    private final SeasonService seasonService;

    SeasonController(SeasonService seasonService) {
        this.seasonService = seasonService;
    }

    @GetMapping
    List<SeasonResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return seasonService.list(actor(jwt), roles(jwt)).stream().map(SeasonResponse::from).toList();
    }

    @GetMapping("/{seasonId}")
    SeasonResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID seasonId) {
        return SeasonResponse.from(seasonService.get(actor(jwt), roles(jwt), seasonId));
    }

    @PostMapping
    ResponseEntity<SeasonResponse> create(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody SeasonRequest request) {
        SeasonResponse created = SeasonResponse.from(seasonService.create(actor(jwt), roles(jwt),
                request.name(), request.startDate(), request.endDate(), request.isActive()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{seasonId}")
    SeasonResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID seasonId,
                          @Valid @RequestBody SeasonRequest request) {
        return SeasonResponse.from(seasonService.update(actor(jwt), roles(jwt), seasonId,
                request.name(), request.startDate(), request.endDate(), request.isActive()));
    }

    @DeleteMapping("/{seasonId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID seasonId) {
        seasonService.delete(actor(jwt), roles(jwt), seasonId);
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
