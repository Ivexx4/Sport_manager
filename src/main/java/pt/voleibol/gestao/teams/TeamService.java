package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.Rules;

/**
 * Base mínima de equipas. Só ADMIN/DIRECAO escrevem; TREINADOR vê as equipas em que é staff.
 *
 * <p>A gestão do plantel e do staff é deliberadamente exclusiva de ADMIN/DIRECAO: um treinador não
 * pode adicionar atletas à própria equipa para contornar o âmbito de autorização dos atletas.
 */
@Service
class TeamService {

    private static final Set<String> STAFF_ROLES = Set.of("TREINADOR", "TREINADOR_ADJUNTO");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    record TeamView(UUID id, String name, String status, UUID seasonId, UUID ageGroupId,
                    Instant createdAt, Instant updatedAt) {
    }

    record StaffView(UUID userId, String name, String email, String role) {
    }

    record RosterView(UUID athleteId, String fullName, String status) {
    }

    private final TeamRepository teams;
    private final SeasonRepository seasons;
    private final AgeGroupRepository ageGroups;

    TeamService(TeamRepository teams, SeasonRepository seasons, AgeGroupRepository ageGroups) {
        this.teams = teams;
        this.seasons = seasons;
        this.ageGroups = ageGroups;
    }

    @Transactional(readOnly = true)
    List<TeamView> list(UUID actorId, Set<String> roles) {
        UUID tenantId = Rules.tenant();
        if (!Rules.isManager(roles) && !roles.contains("TREINADOR")) throw Rules.forbidden();
        return teams.list(tenantId, actorId, Rules.isManager(roles)).stream()
                .map(TeamService::view).toList();
    }

    @Transactional(readOnly = true)
    TeamView get(UUID actorId, Set<String> roles, UUID teamId) {
        return view(requireVisible(Rules.tenant(), teamId, actorId, roles));
    }

    @Transactional
    TeamView create(UUID actorId, Set<String> roles, String name, String status, UUID seasonId,
                    UUID ageGroupId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        String normalizedName = Rules.requiredText(name);
        requireReferences(tenantId, seasonId, ageGroupId);
        if (teams.nameExists(tenantId, normalizedName, null)) throw Rules.conflict("Já existe uma equipa com este nome");
        UUID id = UUID.randomUUID();
        try {
            teams.insert(id, tenantId, normalizedName, Rules.status(status == null ? "ACTIVE" : status, STATUSES),
                    seasonId, ageGroupId, actorId);
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe uma equipa com este nome");
        }
        teams.audit(tenantId, actorId, "TEAM_CREATED", id);
        return view(teams.findById(tenantId, id).orElseThrow(TeamService::notFound));
    }

    @Transactional
    TeamView update(UUID actorId, Set<String> roles, UUID teamId, String name, String status,
                    UUID seasonId, UUID ageGroupId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        TeamRepository.TeamRow current = teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound);
        String normalizedName = Rules.requiredText(name);
        requireReferences(tenantId, seasonId, ageGroupId);
        if (teams.nameExists(tenantId, normalizedName, teamId)) throw Rules.conflict("Já existe uma equipa com este nome");
        try {
            if (!teams.update(tenantId, teamId, normalizedName,
                    status == null || status.isBlank() ? current.status() : Rules.status(status, STATUSES),
                    seasonId, ageGroupId)) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe uma equipa com este nome");
        }
        teams.audit(tenantId, actorId, "TEAM_UPDATED", teamId);
        return view(teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound));
    }

    @Transactional
    void delete(UUID actorId, Set<String> roles, UUID teamId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        // Remoção não destrutiva: desativa e anonimiza, preservando staff e plantel para histórico.
        if (!teams.anonymize(tenantId, teamId)) throw notFound();
        teams.audit(tenantId, actorId, "TEAM_ANONYMIZED", teamId);
    }

    // --- staff ---

    @Transactional(readOnly = true)
    List<StaffView> listStaff(UUID actorId, Set<String> roles, UUID teamId) {
        UUID tenantId = Rules.tenant();
        requireVisible(tenantId, teamId, actorId, roles);
        return teams.listStaff(tenantId, teamId).stream()
                .map(s -> new StaffView(s.userId(), s.name(), s.email(), s.role()))
                .toList();
    }

    @Transactional
    List<StaffView> addStaff(UUID actorId, Set<String> roles, UUID teamId, UUID userId, String role) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        requireVisible(tenantId, teamId, actorId, roles);
        if (!teams.activeMembershipExists(tenantId, userId)) throw Rules.notFound("Utilizador sem papel ativo no clube");
        String normalizedRole = validStaffRole(role);
        if (teams.staffExists(tenantId, teamId, userId)) throw Rules.conflict("Este utilizador já é staff da equipa");
        try {
            teams.insertStaff(tenantId, teamId, userId, normalizedRole, actorId);
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Este utilizador já é staff da equipa");
        }
        teams.audit(tenantId, actorId, "TEAM_STAFF_ADDED", teamId);
        return listStaff(actorId, roles, teamId);
    }

    @Transactional
    void removeStaff(UUID actorId, Set<String> roles, UUID teamId, UUID userId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        requireVisible(tenantId, teamId, actorId, roles);
        if (!teams.deleteStaff(tenantId, teamId, userId)) throw Rules.notFound("Utilizador não é staff da equipa");
        teams.audit(tenantId, actorId, "TEAM_STAFF_REMOVED", teamId);
    }

    // --- plantel ---

    @Transactional(readOnly = true)
    List<RosterView> listAthletes(UUID actorId, Set<String> roles, UUID teamId) {
        UUID tenantId = Rules.tenant();
        requireVisible(tenantId, teamId, actorId, roles);
        return teams.listAthletes(tenantId, teamId).stream()
                .map(a -> new RosterView(a.athleteId(), a.fullName(), a.status()))
                .toList();
    }

    @Transactional
    List<RosterView> addAthleteToTeam(UUID actorId, Set<String> roles, UUID teamId, UUID athleteId,
                                      UUID registrationAgeGroupId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        requireVisible(tenantId, teamId, actorId, roles);
        if (!teams.athleteExists(tenantId, athleteId)) throw Rules.notFound("Atleta não encontrado");
        if (teams.rosterExists(tenantId, teamId, athleteId)) throw Rules.conflict("Este atleta já pertence à equipa");
        TeamRepository.TeamRow team = teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound);
        if (team.seasonId() == null) throw Rules.badRequest("A equipa não está associada a uma época");
        if (team.ageGroupId() == null) throw Rules.badRequest("A equipa não está associada a um escalão");
        SeasonRepository.SeasonRow season = seasons.findById(tenantId, team.seasonId())
                .orElseThrow(SeasonService::notFound);
        AgeGroupRepository.AgeGroupRow ageGroup = ageGroups.findById(tenantId, team.ageGroupId())
                .orElseThrow(AgeGroupService::notFound);
        LocalDate birthDate = teams.birthDate(tenantId, athleteId);
        if (birthDate == null) throw Rules.badRequest("Atleta sem data de nascimento");
        AgeGroupRepository.AgeGroupRow registrationGroup =
                resolveRegistrationGroup(tenantId, season, athleteId, birthDate, registrationAgeGroupId);
        validarRegrasFpV(tenantId, season, ageGroup, athleteId, birthDate);
        teams.saveRegistration(tenantId, season.id(), athleteId, registrationGroup.id(), actorId);
        try {
            teams.insertAthlete(tenantId, teamId, athleteId, actorId);
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Este atleta já pertence à equipa");
        }

        teams.audit(tenantId, actorId, "TEAM_ATHLETE_ADDED", teamId);
        return listAthletes(actorId, roles, teamId);
    }

    private AgeGroupRepository.AgeGroupRow resolveRegistrationGroup(
            UUID tenantId, SeasonRepository.SeasonRow season, UUID athleteId, LocalDate birthDate,
            UUID requestedGroupId) {
        List<AgeGroupRepository.AgeGroupRow> groups = ageGroups.list(tenantId);
        int age = ageAt(birthDate, season);
        if (requestedGroupId == null && (age >= 19 && age <= 21 || age > 35))
            throw Rules.badRequest("É obrigatório indicar o escalão de inscrição para este atleta");
        AgeGroupRepository.AgeGroupRow selected = requestedGroupId == null
                ? groups.stream().filter(g -> eligible(age, g))
                    .findFirst()
                    .or(() -> groups.stream().filter(g -> teams.promotionExists(
                            tenantId, season.id(), g.id(), athleteId)).findFirst())
                    .orElseThrow(() -> Rules.badRequest("Não foi possível determinar o escalão de inscrição"))
                : groups.stream().filter(g -> g.id().equals(requestedGroupId)).findFirst()
                    .orElseThrow(() -> Rules.notFound("Escalão de inscrição não encontrado"));
        if (age < selected.minAge()) throw Rules.badRequest("O atleta não pode ser inscrito num escalão inferior");
        if (selected.maxAge() != null && age > selected.maxAge()
                && !(age >= 19 && "Seniores".equalsIgnoreCase(selected.name()))
                && !teams.promotionExists(tenantId, season.id(), selected.id(), athleteId))
            throw Rules.badRequest("A inscrição em escalão superior exige autorização");
        return selected;
    }

    @Transactional
    void removeAthlete(UUID actorId, Set<String> roles, UUID teamId, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(roles);
        requireVisible(tenantId, teamId, actorId, roles);
        if (!teams.deleteAthlete(tenantId, teamId, athleteId)) throw Rules.notFound("Atleta não pertence à equipa");
        teams.audit(tenantId, actorId, "TEAM_ATHLETE_REMOVED", teamId);
    }

    // --- auxiliares ---

    /**
     * Regra federativa FPV no plantel: o atleta só pode integrar o escalão da época quando o seu ano
     * de nascimento tem uma idade dentro dos limites do escalão na data de referência da época.
     * Atleas mais novos do que o escalão (subida) dependem de autorização excepcional; atletas mais
     * velhos do que o escalão não podem jogar em escalão inferior.
     */
    private void validarRegrasFpV(UUID tenantId, SeasonRepository.SeasonRow season,
                                 AgeGroupRepository.AgeGroupRow ageGroup, UUID athleteId, LocalDate birthDate) {
        int age = ageAt(birthDate, season);
        if (isEligibleForSeniors(season, ageGroup, birthDate)) return;
        if (age < ageGroup.minAge())
            throw Rules.badRequest("Proibido atuar em escalão inferior conforme regulamento FPV");
        if (ageGroup.maxAge() != null && age > ageGroup.maxAge()
                && !teams.promotionExists(tenantId, season.id(), ageGroup.id(), athleteId))
            throw Rules.badRequest("Subida de escalão excepcional não autorizada pela época");
    }

    static boolean isEligibleForSeniors(SeasonRepository.SeasonRow season,
                                        AgeGroupRepository.AgeGroupRow ageGroup, LocalDate birthDate) {
        if (!"Seniores".equalsIgnoreCase(ageGroup.name())) return false;
        return ageAt(birthDate, season) >= 19;
    }

    static boolean isEligibleForSeniors(SeasonRepository.SeasonRow season,
                                        AgeGroupRepository.AgeGroupRow ageGroup, int birthYear) {
        return isEligibleForSeniors(season, ageGroup, LocalDate.of(birthYear, 1, 1));
    }

    private static int ageAt(LocalDate birthDate, SeasonRepository.SeasonRow season) {
        LocalDate reference = season.cutoffDate() != null ? season.cutoffDate() : LocalDate.now();
        return Period.between(birthDate, reference).getYears();
    }

    private static boolean eligible(int age, AgeGroupRepository.AgeGroupRow group) {
        return age >= group.minAge() && (group.maxAge() == null || age <= group.maxAge());
    }

    private TeamRepository.TeamRow requireVisible(UUID tenantId, UUID teamId, UUID actorId, Set<String> roles) {
        if (Rules.isManager(roles)) return teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound);
        if (roles.contains("TREINADOR")) return teams.findVisible(tenantId, teamId, actorId, false).orElseThrow(TeamService::notFound);
        throw Rules.forbidden();
    }

    /** A época e o escalão têm de existir no clube e não podem estar anonimizados. */
    private void requireReferences(UUID tenantId, UUID seasonId, UUID ageGroupId) {
        if (seasonId != null && !teams.seasonExists(tenantId, seasonId)) throw Rules.notFound("Época não encontrada");
        if (ageGroupId != null && !teams.ageGroupExists(tenantId, ageGroupId)) throw Rules.notFound("Escalão não encontrado");
    }

    private static String validStaffRole(String role) {
        if (role == null || role.isBlank()) return "TREINADOR";
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!STAFF_ROLES.contains(normalized)) throw Rules.badRequest("Papel de staff inválido");
        return normalized;
    }

    private static ResponseStatusException notFound() {
        return Rules.notFound("Equipa não encontrada");
    }

    private static TeamView view(TeamRepository.TeamRow row) {
        return new TeamView(row.id(), row.name(), row.status(), row.seasonId(), row.ageGroupId(),
                row.createdAt(), row.updatedAt());
    }
}
