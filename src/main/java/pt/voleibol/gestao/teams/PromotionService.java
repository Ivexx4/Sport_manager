package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.AuditLog;
import pt.voleibol.gestao.shared.CurrentUser;
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

    private final PromotionRepository promotions;
    private final SeasonRepository seasons;
    private final TeamRepository teams;
    private final AuditLog audit;

    PromotionService(PromotionRepository promotions, SeasonRepository seasons, TeamRepository teams,
                     AuditLog audit) {
        this.promotions = promotions;
        this.seasons = seasons;
        this.teams = teams;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    List<PromotionView> list(CurrentUser user, UUID seasonId, UUID athleteId) {
        Rules.requireManager(user);
        UUID tenantId = Rules.tenant();
        requireSeason(tenantId, seasonId);
        return promotions.list(tenantId, seasonId, athleteId).stream().map(PromotionService::view).toList();
    }

    @Transactional
    PromotionView grant(CurrentUser user, UUID seasonId, UUID athleteId, UUID ageGroupId,
                        String reason) {
        Rules.requireManager(user);
        UUID tenantId = Rules.tenant();
        requireSeason(tenantId, seasonId);
        if (!teams.athleteExists(tenantId, athleteId)) throw Rules.notFound("Atleta não encontrado");
        if (!teams.ageGroupExists(tenantId, ageGroupId)) throw Rules.notFound("Escalão não encontrado");

        PromotionRepository.PromotionRow existing =
                promotions.find(tenantId, seasonId, ageGroupId, athleteId).orElse(null);
        if (existing != null && PromotionStatus.ACTIVE.name().equals(existing.status()))
            throw Rules.conflict("Já existe uma autorização ativa para este atleta neste escalão");
        if (existing != null) {
            promotions.setStatus(tenantId, seasonId, existing.id(), PromotionStatus.ACTIVE.name());
            audit.record(tenantId, user.id(), "SEASON_PROMOTION_GRANTED", "season_promotion", existing.id());
            return view(promotions.find(tenantId, seasonId, existing.id()).orElseThrow(PromotionService::notFound));
        }

        UUID id = UUID.randomUUID();
        promotions.insert(id, tenantId, seasonId, athleteId, ageGroupId, trimToNull(reason), user.id());
        audit.record(tenantId, user.id(), "SEASON_PROMOTION_GRANTED", "season_promotion", id);
        return view(promotions.find(tenantId, seasonId, id).orElseThrow(PromotionService::notFound));
    }

    @Transactional
    void setStatus(CurrentUser user, UUID seasonId, UUID promotionId, String status) {
        Rules.requireManager(user);
        UUID tenantId = Rules.tenant();
        requireSeason(tenantId, seasonId);
        String normalized = Rules.parse(PromotionStatus.class, status, "Estado").name();
        if (!promotions.setStatus(tenantId, seasonId, promotionId, normalized)) throw notFound();
        audit.record(tenantId, user.id(),
                PromotionStatus.REVOKED.name().equals(normalized)
                        ? "SEASON_PROMOTION_REVOKED" : "SEASON_PROMOTION_GRANTED",
                "season_promotion", promotionId);
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
