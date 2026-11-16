package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.Rules;

/**
 * Autorizações excepcionais de subida de escalão: concessão, listagem e revogação. Só
 * ADMIN/DIRECAO as gerem e cada operação fica em {@code audit_log}.
 *
 * <p>Revogar não remove plantéis nem inscrições já criados: apenas bloqueia as operações
 * seguintes. Reativar uma autorização revogada preserva o motivo original.
 */
@Service
class PromotionService {

    record PromotionView(UUID id, UUID athleteId, String athleteName, UUID ageGroupId, String ageGroupCode,
                         String ageGroupName, String status, String reason, UUID authorizedBy,
                         Instant createdAt, Instant updatedAt) {
    }

    private static final Set<String> STATUSES = Set.of("ACTIVE", "REVOKED");

    private final PromotionRepository promotions;
    private final SeasonRepository seasons;
    private final TeamRepository teams;

    PromotionService(PromotionRepository promotions, SeasonRepository seasons, TeamRepository teams) {
        this.promotions = promotions;
        this.seasons = seasons;
        this.teams = teams;
    }

    @Transactional(readOnly = true)
    List<PromotionView> list(UUID actorId, Set<String> roles, UUID seasonId, UUID athleteId) {
        Rules.requireManager(roles);
        UUID tenantId = Rules.tenant();
        requireSeason(tenantId, seasonId);
        return promotions.list(tenantId, seasonId, athleteId).stream().map(PromotionService::view).toList();
    }

    @Transactional
    PromotionView grant(UUID actorId, Set<String> roles, UUID seasonId, UUID athleteId, UUID ageGroupId,
                        String reason) {
        Rules.requireManager(roles);
        UUID tenantId = Rules.tenant();
        requireSeason(tenantId, seasonId);
        if (!teams.athleteExists(tenantId, athleteId)) throw Rules.notFound("Atleta não encontrado");
        if (!teams.ageGroupExists(tenantId, ageGroupId)) throw Rules.notFound("Escalão não encontrado");

        PromotionRepository.PromotionRow existing =
                promotions.find(tenantId, seasonId, ageGroupId, athleteId).orElse(null);
        if (existing != null && "ACTIVE".equals(existing.status()))
            throw Rules.conflict("Já existe uma autorização ativa para este atleta neste escalão");
        if (existing != null) {
            promotions.setStatus(tenantId, seasonId, existing.id(), "ACTIVE");
            promotions.audit(tenantId, actorId, "SEASON_PROMOTION_GRANTED", existing.id());
            return view(promotions.find(tenantId, seasonId, existing.id()).orElseThrow(PromotionService::notFound));
        }

        UUID id = UUID.randomUUID();
        promotions.insert(id, tenantId, seasonId, athleteId, ageGroupId, trimToNull(reason), actorId);
        promotions.audit(tenantId, actorId, "SEASON_PROMOTION_GRANTED", id);
        return view(promotions.find(tenantId, seasonId, id).orElseThrow(PromotionService::notFound));
    }

    @Transactional
    void setStatus(UUID actorId, Set<String> roles, UUID seasonId, UUID promotionId, String status) {
        Rules.requireManager(roles);
        UUID tenantId = Rules.tenant();
        requireSeason(tenantId, seasonId);
        String normalized = Rules.status(status == null ? "" : status, STATUSES);
        if (!promotions.setStatus(tenantId, seasonId, promotionId, normalized)) throw notFound();
        promotions.audit(tenantId, actorId,
                "REVOKED".equals(normalized) ? "SEASON_PROMOTION_REVOKED" : "SEASON_PROMOTION_GRANTED",
                promotionId);
    }

    private void requireSeason(UUID tenantId, UUID seasonId) {
        if (seasons.findById(tenantId, seasonId).isEmpty()) throw SeasonService.notFound();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException notFound() {
        return Rules.notFound("Autorização não encontrada");
    }

    private static PromotionView view(PromotionRepository.PromotionRow row) {
        return new PromotionView(row.id(), row.athleteId(), row.athleteName(), row.ageGroupId(),
                row.ageGroupCode(), row.ageGroupName(), row.status(), row.reason(), row.authorizedBy(),
                row.createdAt(), row.updatedAt());
    }
}
