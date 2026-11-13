package pt.voleibol.gestao.teams;

import java.time.Instant;
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
 * Base mínima de equipas. Só ADMIN/DIRECAO escrevem; TREINADOR vê as equipas em que é staff.
 *
 * <p>A gestão do plantel e do staff é deliberadamente exclusiva de ADMIN/DIRECAO: um treinador não
 * pode adicionar atletas à própria equipa para contornar o âmbito de autorização dos atletas.
 */
@Service
class TeamService {

    private static final Set<String> MANAGERS = Set.of("ADMIN", "DIRECAO");
    private static final Set<String> STAFF_ROLES = Set.of("TREINADOR", "TREINADOR_ADJUNTO");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    record TeamView(UUID id, String name, String status, Instant createdAt, Instant updatedAt) {
    }

    record StaffView(UUID userId, String name, String email, String role) {
    }

    record RosterView(UUID athleteId, String fullName, String status) {
    }

    private final TeamRepository teams;

    TeamService(TeamRepository teams) {
        this.teams = teams;
    }

    @Transactional(readOnly = true)
    List<TeamView> list(UUID actorId, Set<String> roles) {
        UUID tenantId = requireTenant();
        if (!isManager(roles) && !roles.contains("TREINADOR")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return teams.list(tenantId, actorId, isManager(roles)).stream()
                .map(TeamService::view).toList();
    }

    @Transactional(readOnly = true)
    TeamView get(UUID actorId, Set<String> roles, UUID teamId) {
        return view(requireVisible(requireTenant(), teamId, actorId, roles));
    }

    @Transactional
    TeamView create(UUID actorId, Set<String> roles, String name, String status) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        String normalizedName = requiredText(name);
        if (teams.nameExists(tenantId, normalizedName, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma equipa com este nome");
        }
        UUID id = UUID.randomUUID();
        try {
            teams.insert(id, tenantId, normalizedName, validStatus(status == null ? "ACTIVE" : status), actorId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma equipa com este nome");
        }
        teams.audit(tenantId, actorId, "TEAM_CREATED", id);
        return view(teams.findById(tenantId, id).orElseThrow(TeamService::notFound));
    }

    @Transactional
    TeamView update(UUID actorId, Set<String> roles, UUID teamId, String name, String status) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        TeamRepository.TeamRow current = teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound);
        String normalizedName = requiredText(name);
        if (teams.nameExists(tenantId, normalizedName, teamId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma equipa com este nome");
        }
        try {
            if (!teams.update(tenantId, teamId, normalizedName,
                    status == null || status.isBlank() ? current.status() : validStatus(status))) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma equipa com este nome");
        }
        teams.audit(tenantId, actorId, "TEAM_UPDATED", teamId);
        return view(teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound));
    }

    @Transactional
    void delete(UUID actorId, Set<String> roles, UUID teamId) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        // Remoção não destrutiva: desativa e anonimiza, preservando staff e plantel para histórico.
        if (!teams.anonymize(tenantId, teamId)) {
            throw notFound();
        }
        teams.audit(tenantId, actorId, "TEAM_ANONYMIZED", teamId);
    }

    // --- staff ---

    @Transactional(readOnly = true)
    List<StaffView> listStaff(UUID actorId, Set<String> roles, UUID teamId) {
        UUID tenantId = requireTenant();
        requireVisible(tenantId, teamId, actorId, roles);
        return teams.listStaff(tenantId, teamId).stream()
                .map(s -> new StaffView(s.userId(), s.name(), s.email(), s.role()))
                .toList();
    }

    @Transactional
    List<StaffView> addStaff(UUID actorId, Set<String> roles, UUID teamId, UUID userId, String role) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireVisible(tenantId, teamId, actorId, roles);
        if (!teams.activeMembershipExists(tenantId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilizador sem papel ativo no clube");
        }
        String normalizedRole = validStaffRole(role);
        if (teams.staffExists(tenantId, teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este utilizador já é staff da equipa");
        }
        try {
            teams.insertStaff(tenantId, teamId, userId, normalizedRole, actorId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este utilizador já é staff da equipa");
        }
        teams.audit(tenantId, actorId, "TEAM_STAFF_ADDED", teamId);
        return listStaff(actorId, roles, teamId);
    }

    @Transactional
    void removeStaff(UUID actorId, Set<String> roles, UUID teamId, UUID userId) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireVisible(tenantId, teamId, actorId, roles);
        if (!teams.deleteStaff(tenantId, teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilizador não é staff da equipa");
        }
        teams.audit(tenantId, actorId, "TEAM_STAFF_REMOVED", teamId);
    }

    // --- plantel ---

    @Transactional(readOnly = true)
    List<RosterView> listAthletes(UUID actorId, Set<String> roles, UUID teamId) {
        UUID tenantId = requireTenant();
        requireVisible(tenantId, teamId, actorId, roles);
        return teams.listAthletes(tenantId, teamId).stream()
                .map(a -> new RosterView(a.athleteId(), a.fullName(), a.status()))
                .toList();
    }

    @Transactional
    List<RosterView> addAthlete(UUID actorId, Set<String> roles, UUID teamId, UUID athleteId) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireVisible(tenantId, teamId, actorId, roles);
        if (!teams.athleteExists(tenantId, athleteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Atleta não encontrado");
        }
        if (teams.rosterExists(tenantId, teamId, athleteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este atleta já pertence à equipa");
        }
        try {
            teams.insertAthlete(tenantId, teamId, athleteId, actorId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este atleta já pertence à equipa");
        }
        teams.audit(tenantId, actorId, "TEAM_ATHLETE_ADDED", teamId);
        return listAthletes(actorId, roles, teamId);
    }

    @Transactional
    void removeAthlete(UUID actorId, Set<String> roles, UUID teamId, UUID athleteId) {
        UUID tenantId = requireTenant();
        requireManager(roles);
        requireVisible(tenantId, teamId, actorId, roles);
        if (!teams.deleteAthlete(tenantId, teamId, athleteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Atleta não pertence à equipa");
        }
        teams.audit(tenantId, actorId, "TEAM_ATHLETE_REMOVED", teamId);
    }

    // --- auxiliares ---

    private TeamRepository.TeamRow requireVisible(UUID tenantId, UUID teamId, UUID actorId, Set<String> roles) {
        if (isManager(roles)) {
            return teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound);
        }
        if (roles.contains("TREINADOR")) {
            return teams.findVisible(tenantId, teamId, actorId, false).orElseThrow(TeamService::notFound);
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static void requireManager(Set<String> roles) {
        if (!isManager(roles)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static boolean isManager(Set<String> roles) {
        return roles.stream().anyMatch(MANAGERS::contains);
    }

    private static String validStatus(String status) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado inválido");
        }
        return normalized;
    }

    private static String validStaffRole(String role) {
        if (role == null || role.isBlank()) {
            return "TREINADOR";
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!STAFF_ROLES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Papel de staff inválido");
        }
        return normalized;
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

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipa não encontrada");
    }

    private static TeamView view(TeamRepository.TeamRow row) {
        return new TeamView(row.id(), row.name(), row.status(), row.createdAt(), row.updatedAt());
    }
}
