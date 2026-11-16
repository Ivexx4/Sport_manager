package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.Rules;

/**
 * Épocas do clube (FPV). Qualquer membro autenticado lê; a criação, edição e remoção são exclusivas
 * de ADMIN/DIRECAO.
 */
@Service
class SeasonService {

    record SeasonView(UUID id, String name, LocalDate startDate, LocalDate endDate, LocalDate cutoffDate, boolean isActive,
                      Instant createdAt, Instant updatedAt) {
    }

    private final SeasonRepository seasons;

    SeasonService(SeasonRepository seasons) {
        this.seasons = seasons;
    }

    @Transactional(readOnly = true)
    List<SeasonView> list(UUID actorId, Set<String> roles) {
        Rules.requireMember(roles);
        UUID tenantId = Rules.tenant();
        return seasons.list(tenantId).stream().map(SeasonService::view).toList();
    }

    @Transactional(readOnly = true)
    SeasonView get(UUID actorId, Set<String> roles, UUID seasonId) {
        Rules.requireMember(roles);
        return view(seasons.findById(Rules.tenant(), seasonId).orElseThrow(SeasonService::notFound));
    }

    @Transactional
    SeasonView create(UUID actorId, Set<String> roles, String name, LocalDate startDate,
                      LocalDate endDate, Boolean isActive) {
        return create(actorId, roles, name, startDate, endDate, null, isActive);
    }

    @Transactional
    SeasonView create(UUID actorId, Set<String> roles, String name, LocalDate startDate,
                      LocalDate endDate, LocalDate cutoffDate, Boolean isActive) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        String normalizedName = Rules.requiredText(name);
        requireValidPeriod(startDate, endDate);
        cutoffDate = defaultCutoff(cutoffDate);
        if (seasons.nameExists(tenantId, normalizedName, null)) throw Rules.conflict("Já existe uma época com este nome");
        UUID id = UUID.randomUUID();
        try {
            seasons.insert(id, tenantId, new SeasonRepository.Data(normalizedName, startDate, endDate, cutoffDate,
                    isActive == null || isActive), actorId);
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe uma época com este nome");
        }
        seasons.audit(tenantId, actorId, "SEASON_CREATED", id);
        return view(seasons.findById(tenantId, id).orElseThrow(SeasonService::notFound));
    }

    @Transactional
    SeasonView update(UUID actorId, Set<String> roles, UUID seasonId, String name,
                      LocalDate startDate, LocalDate endDate, Boolean isActive) {
        return update(actorId, roles, seasonId, name, startDate, endDate, null, isActive);
    }

    @Transactional
    SeasonView update(UUID actorId, Set<String> roles, UUID seasonId, String name,
                      LocalDate startDate, LocalDate endDate, LocalDate cutoffDate, Boolean isActive) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        SeasonRepository.SeasonRow current =
                seasons.findById(tenantId, seasonId).orElseThrow(SeasonService::notFound);
        String normalizedName = Rules.requiredText(name);
        requireValidPeriod(startDate, endDate);
        cutoffDate = defaultCutoff(cutoffDate);
        if (seasons.nameExists(tenantId, normalizedName, seasonId)) throw Rules.conflict("Já existe uma época com este nome");
        try {
            if (!seasons.update(tenantId, seasonId, new SeasonRepository.Data(normalizedName, startDate, endDate,
                    cutoffDate, isActive == null ? current.isActive() : isActive))) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe uma época com este nome");
        }
        seasons.audit(tenantId, actorId, "SEASON_UPDATED", seasonId);
        return view(seasons.findById(tenantId, seasonId).orElseThrow(SeasonService::notFound));
    }

    @Transactional
    void delete(UUID actorId, Set<String> roles, UUID seasonId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        // Remoção não destrutiva: marca a época como não ativa e anonimiza, preservando as equipas
        // associadas para histórico.
        if (!seasons.anonymize(tenantId, seasonId)) throw notFound();
        seasons.audit(tenantId, actorId, "SEASON_ANONYMIZED", seasonId);
    }

    // --- auxiliares ---

    private static void requireValidPeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate))
            throw Rules.badRequest("A data de fim não pode ser anterior à data de início");
    }

    private static LocalDate defaultCutoff(LocalDate cutoffDate) {
        return cutoffDate == null ? LocalDate.of(LocalDate.now().getYear() + 1, 1, 1) : cutoffDate;
    }

    static ResponseStatusException notFound() {
        return Rules.notFound("Época não encontrada");
    }

    private static SeasonView view(SeasonRepository.SeasonRow row) {
        return new SeasonView(row.id(), row.name(), row.startDate(), row.endDate(), row.cutoffDate(), row.isActive(),
                row.createdAt(), row.updatedAt());
    }
}
