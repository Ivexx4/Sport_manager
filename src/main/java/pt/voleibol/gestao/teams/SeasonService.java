package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.AuditLog;
import pt.voleibol.gestao.shared.CurrentUser;
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
    private final SeasonEligibility eligibility;
    private final AuditLog audit;

    SeasonService(SeasonRepository seasons, SeasonEligibility eligibility, AuditLog audit) {
        this.seasons = seasons;
        this.eligibility = eligibility;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    List<SeasonView> list(CurrentUser user) {
        Rules.requireMember(user);
        UUID tenantId = Rules.tenant();
        return seasons.list(tenantId).stream().map(SeasonService::view).toList();
    }

    @Transactional(readOnly = true)
    SeasonView get(CurrentUser user, UUID seasonId) {
        Rules.requireMember(user);
        return view(seasons.findById(Rules.tenant(), seasonId).orElseThrow(SeasonService::notFound));
    }

    @Transactional
    SeasonView create(CurrentUser user, String name, LocalDate startDate,
                      LocalDate endDate, Boolean isActive) {
        return create(user, name, startDate, endDate, null, isActive);
    }

    @Transactional
    SeasonView create(CurrentUser user, String name, LocalDate startDate,
                      LocalDate endDate, LocalDate cutoffDate, Boolean isActive) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        String normalizedName = Rules.requiredText(name);
        requireValidPeriod(startDate, endDate);
        cutoffDate = defaultCutoff(cutoffDate, startDate);
        if (seasons.nameExists(tenantId, normalizedName, null)) throw Rules.conflict("Já existe uma época com este nome");
        UUID id = UUID.randomUUID();
        try {
            seasons.insert(id, tenantId, new SeasonRepository.Data(normalizedName, startDate, endDate, cutoffDate,
                    isActive == null || isActive), user.id());
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe uma época com este nome");
        }
        audit.record(tenantId, user.id(), "SEASON_CREATED", "season", id);
        return view(seasons.findById(tenantId, id).orElseThrow(SeasonService::notFound));
    }

    @Transactional
    SeasonView update(CurrentUser user, UUID seasonId, String name,
                      LocalDate startDate, LocalDate endDate, Boolean isActive) {
        return update(user, seasonId, name, startDate, endDate, null, isActive);
    }

    @Transactional
    SeasonView update(CurrentUser user, UUID seasonId, String name,
                      LocalDate startDate, LocalDate endDate, LocalDate cutoffDate, Boolean isActive) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        SeasonRepository.SeasonRow current =
                seasons.findById(tenantId, seasonId).orElseThrow(SeasonService::notFound);
        String normalizedName = Rules.requiredText(name);
        requireValidPeriod(startDate, endDate);
        cutoffDate = defaultCutoff(cutoffDate, startDate);
        if (seasons.nameExists(tenantId, normalizedName, seasonId)) throw Rules.conflict("Já existe uma época com este nome");
        boolean referenceChanged = !Objects.equals(current.startDate(), startDate)
                || !Objects.equals(current.cutoffDate(), cutoffDate);
        try {
            if (!seasons.update(tenantId, seasonId, new SeasonRepository.Data(normalizedName, startDate, endDate,
                    cutoffDate, isActive == null ? current.isActive() : isActive))) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe uma época com este nome");
        }
        // Mudar a data de referência recalcula a idade de todos os atletas da época: a validação corre
        // depois da escrita, dentro da transação, e desfaz a alteração.
        if (referenceChanged) eligibility.requireSeasonStillValid(tenantId, seasonId);
        audit.record(tenantId, user.id(), "SEASON_UPDATED", "season", seasonId);
        return view(seasons.findById(tenantId, seasonId).orElseThrow(SeasonService::notFound));
    }

    @Transactional
    void delete(CurrentUser user, UUID seasonId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        // Remoção não destrutiva: marca a época como não ativa e anonimiza, preservando as equipas
        // associadas para histórico.
        if (!seasons.anonymize(tenantId, seasonId)) throw notFound();
        audit.record(tenantId, user.id(), "SEASON_ANONYMIZED", "season", seasonId);
    }

    // --- auxiliares ---

    private static void requireValidPeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate))
            throw Rules.badRequest("A data de fim não pode ser anterior à data de início");
    }

    /**
     * Data de referência para o cálculo da idade: a indicada, o início da época ou, em último caso, o
     * primeiro dia do ano seguinte.
     */
    private static LocalDate defaultCutoff(LocalDate cutoffDate, LocalDate startDate) {
        if (cutoffDate != null) return cutoffDate;
        if (startDate != null) return startDate;
        return LocalDate.of(LocalDate.now().getYear() + 1, 1, 1);
    }

    static ResponseStatusException notFound() {
        return Rules.notFound("Época não encontrada");
    }

    private static SeasonView view(SeasonRepository.SeasonRow row) {
        return new SeasonView(row.id(), row.name(), row.startDate(), row.endDate(), row.cutoffDate(), row.isActive(),
                row.createdAt(), row.updatedAt());
    }
}
