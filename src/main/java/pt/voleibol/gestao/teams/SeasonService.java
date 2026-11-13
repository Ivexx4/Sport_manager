package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Épocas do clube (FPV). Qualquer membro autenticado lê; a criação, edição e remoção são exclusivas
 * de ADMIN/DIRECAO.
 */
@Service
class SeasonService {

    private static final Set<String> MANAGERS = Set.of("ADMIN", "DIRECAO");

    record SeasonView(UUID id, String name, LocalDate startDate, LocalDate endDate, boolean isActive,
                      Instant createdAt, Instant updatedAt) {
    }

    private final SeasonRepository seasons;

    SeasonService(SeasonRepository seasons) {
        this.seasons = seasons;
    }

    @Transactional(readOnly = true)
    List<SeasonView> list(UUID actorId, Set<String> roles) {
        requireMember(roles);
        UUID tenantId = requireTenant();
        return seasons.list(tenantId).stream().map(SeasonService::view).toList();
    }

    @Transactional(readOnly = true)
    SeasonView get(UUID actorId, Set<String> roles, UUID seasonId) {
        requireMember(roles);
        return view(seasons.findById(requireTenant(), seasonId).orElseThrow(SeasonService::notFound));
    }

    @Transactional
    SeasonView create(UUID actorId, Set<String> roles, String name, LocalDate startDate,
                      LocalDate endDate, Boolean isActive) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        String normalizedName = requiredText(name);
        requireValidPeriod(startDate, endDate);
        if (seasons.nameExists(tenantId, normalizedName, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma época com este nome");
        }
        UUID id = UUID.randomUUID();
        try {
            seasons.insert(id, tenantId, new SeasonRepository.Data(normalizedName, startDate, endDate,
                    isActive == null || isActive), actorId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma época com este nome");
        }
        seasons.audit(tenantId, actorId, "SEASON_CREATED", id);
        return view(seasons.findById(tenantId, id).orElseThrow(SeasonService::notFound));
    }

    @Transactional
    SeasonView update(UUID actorId, Set<String> roles, UUID seasonId, String name,
                      LocalDate startDate, LocalDate endDate, Boolean isActive) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        SeasonRepository.SeasonRow current =
                seasons.findById(tenantId, seasonId).orElseThrow(SeasonService::notFound);
        String normalizedName = requiredText(name);
        requireValidPeriod(startDate, endDate);
        if (seasons.nameExists(tenantId, normalizedName, seasonId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma época com este nome");
        }
        try {
            if (!seasons.update(tenantId, seasonId, new SeasonRepository.Data(normalizedName, startDate, endDate,
                    isActive == null ? current.isActive() : isActive))) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma época com este nome");
        }
        seasons.audit(tenantId, actorId, "SEASON_UPDATED", seasonId);
        return view(seasons.findById(tenantId, seasonId).orElseThrow(SeasonService::notFound));
    }

    @Transactional
    void delete(UUID actorId, Set<String> roles, UUID seasonId) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        // Remoção não destrutiva: marca a época como não ativa e anonimiza, preservando as equipas
        // associadas para histórico.
        if (!seasons.anonymize(tenantId, seasonId)) {
            throw notFound();
        }
        seasons.audit(tenantId, actorId, "SEASON_ANONYMIZED", seasonId);
    }

    // --- auxiliares ---

    private static void requireMember(Set<String> roles) {
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static void requireManager(Set<String> roles) {
        if (roles.stream().noneMatch(MANAGERS::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static void requireValidPeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A data de fim não pode ser anterior à data de início");
        }
    }

    private static String requiredText(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome obrigatório");
        }
        return value.trim();
    }

    private static UUID requireTenant() {
        return TenantContext.current().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube: usa o subdomínio do clube"));
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Época não encontrada");
    }

    private static SeasonView view(SeasonRepository.SeasonRow row) {
        return new SeasonView(row.id(), row.name(), row.startDate(), row.endDate(), row.isActive(),
                row.createdAt(), row.updatedAt());
    }
}
