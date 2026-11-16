package pt.voleibol.gestao.teams;

import java.time.LocalDate;
import java.util.List;
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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import pt.voleibol.gestao.shared.Auth;

/**
 * Épocas do clube e autorizações de subida de escalão: CRUD das épocas e gestão das promoções.
 * A leitura das épocas é aberta a qualquer membro autenticado; a escrita e todas as promoções são
 * exclusivas de ADMIN/DIRECAO, validadas no serviço.
 */
@RestController
@RequestMapping("/api/v1/seasons")
class SeasonController {

    record SeasonRequest(@NotBlank @Size(max = 120) String name,
                         LocalDate startDate,
                         LocalDate endDate,
                         LocalDate cutoffDate,
                         Boolean isActive) {
    }

    record PromotionRequest(@NotNull UUID athleteId,
                            @NotNull UUID ageGroupId,
                            @Size(max = 200) String reason) {
    }

    record PromotionStatusRequest(@NotBlank @Pattern(regexp = "(?i)ACTIVE|REVOKED") String status) {
    }

    private final SeasonService seasonService;
    private final PromotionService promotionService;

    SeasonController(SeasonService seasonService, PromotionService promotionService) {
        this.seasonService = seasonService;
        this.promotionService = promotionService;
    }

    @GetMapping
    List<SeasonService.SeasonView> list(@AuthenticationPrincipal Jwt jwt) {
        return seasonService.list(Auth.actor(jwt), Auth.roles(jwt));
    }

    @GetMapping("/{seasonId}")
    SeasonService.SeasonView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID seasonId) {
        return seasonService.get(Auth.actor(jwt), Auth.roles(jwt), seasonId);
    }

    @PostMapping
    ResponseEntity<SeasonService.SeasonView> create(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody SeasonRequest request) {
        SeasonService.SeasonView created = seasonService.create(Auth.actor(jwt), Auth.roles(jwt),
                request.name(), request.startDate(), request.endDate(), request.cutoffDate(),
                request.isActive());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{seasonId}")
    SeasonService.SeasonView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID seasonId,
                                    @Valid @RequestBody SeasonRequest request) {
        return seasonService.update(Auth.actor(jwt), Auth.roles(jwt), seasonId, request.name(),
                request.startDate(), request.endDate(), request.cutoffDate(), request.isActive());
    }

    @DeleteMapping("/{seasonId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID seasonId) {
        seasonService.delete(Auth.actor(jwt), Auth.roles(jwt), seasonId);
        return ResponseEntity.noContent().build();
    }

    // --- autorizações excepcionais de subida de escalão ---

    @GetMapping("/{seasonId}/promotions")
    List<PromotionService.PromotionView> listPromotions(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable UUID seasonId,
                                                        @RequestParam(required = false) UUID athleteId) {
        return promotionService.list(Auth.actor(jwt), Auth.roles(jwt), seasonId, athleteId);
    }

    @PostMapping("/{seasonId}/promotions")
    ResponseEntity<PromotionService.PromotionView> grantPromotion(@AuthenticationPrincipal Jwt jwt,
                                                                  @PathVariable UUID seasonId,
                                                                  @Valid @RequestBody PromotionRequest request) {
        PromotionService.PromotionView created = promotionService.grant(Auth.actor(jwt), Auth.roles(jwt),
                seasonId, request.athleteId(), request.ageGroupId(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{seasonId}/promotions/{promotionId}")
    ResponseEntity<Void> setPromotionStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID seasonId,
                                            @PathVariable UUID promotionId,
                                            @Valid @RequestBody PromotionStatusRequest request) {
        promotionService.setStatus(Auth.actor(jwt), Auth.roles(jwt), seasonId, promotionId, request.status());
        return ResponseEntity.noContent().build();
    }
}
