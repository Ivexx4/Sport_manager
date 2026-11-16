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
import pt.voleibol.gestao.shared.Role;
import pt.voleibol.gestao.shared.Rules;

/**
 * Base mínima de equipas. Só ADMIN/DIRECAO escrevem; TREINADOR vê as equipas em que é staff.
 *
 * <p>A gestão do plantel e do staff é deliberadamente exclusiva de ADMIN/DIRECAO: um treinador não
 * pode adicionar atletas à própria equipa para contornar o âmbito de autorização dos atletas.
 */
@Service
class TeamService {

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
    private final PromotionRepository promotions;
    private final SeasonEligibility eligibility;
    private final AuditLog audit;

    TeamService(TeamRepository teams, SeasonRepository seasons, AgeGroupRepository ageGroups,
                PromotionRepository promotions, SeasonEligibility eligibility, AuditLog audit) {
        this.teams = teams;
        this.seasons = seasons;
        this.ageGroups = ageGroups;
        this.promotions = promotions;
        this.eligibility = eligibility;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    List<TeamView> list(CurrentUser user) {
        UUID tenantId = Rules.tenant();
        if (!user.isManager() && !user.has(Role.TREINADOR)) throw Rules.forbidden();
        return teams.list(tenantId, user.id(), user.isManager()).stream()
                .map(TeamService::view).toList();
    }

    @Transactional(readOnly = true)
    TeamView get(CurrentUser user, UUID teamId) {
        return view(requireVisible(Rules.tenant(), teamId, user));
    }

    @Transactional
    TeamView create(CurrentUser user, String name, String status, UUID seasonId,
                    UUID ageGroupId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        String normalizedName = Rules.requiredText(name);
        requireReferences(tenantId, seasonId, ageGroupId);
        if (teams.nameExists(tenantId, normalizedName, null)) throw Rules.conflict("Já existe uma equipa com este nome");
        UUID id = UUID.randomUUID();
        try {
            teams.insert(id, tenantId, normalizedName,
                    Rules.parse(TeamStatus.class, status, "Estado", TeamStatus.DEFAULT).name(),
                    seasonId, ageGroupId, user.id());
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe uma equipa com este nome");
        }
        audit.record(tenantId, user.id(), "TEAM_CREATED", "team", id);
        return view(teams.findById(tenantId, id).orElseThrow(TeamService::notFound));
    }

    @Transactional
    TeamView update(CurrentUser user, UUID teamId, String name, String status,
                    UUID seasonId, UUID ageGroupId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        TeamRepository.TeamRow current = teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound);
        String normalizedName = Rules.requiredText(name);
        requireReferences(tenantId, seasonId, ageGroupId);
        if (teams.nameExists(tenantId, normalizedName, teamId)) throw Rules.conflict("Já existe uma equipa com este nome");
        boolean contextChanged = !Objects.equals(current.seasonId(), seasonId)
                || !Objects.equals(current.ageGroupId(), ageGroupId);
        try {
            if (!teams.update(tenantId, teamId, normalizedName,
                    status == null || status.isBlank() ? current.status()
                            : Rules.parse(TeamStatus.class, status, "Estado").name(),
                    seasonId, ageGroupId)) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe uma equipa com este nome");
        }
        // Mudar a época ou o escalão pode invalidar o plantel: a validação corre depois da escrita,
        // dentro da transação, e desfaz a alteração.
        if (contextChanged) eligibility.requireTeamStillValid(tenantId, teamId);
        audit.record(tenantId, user.id(), "TEAM_UPDATED", "team", teamId);
        return view(teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound));
    }

    @Transactional
    void delete(CurrentUser user, UUID teamId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        // Remoção não destrutiva: desativa e anonimiza, preservando staff e plantel para histórico.
        if (!teams.anonymize(tenantId, teamId)) throw notFound();
        audit.record(tenantId, user.id(), "TEAM_ANONYMIZED", "team", teamId);
    }

    // --- staff ---

    @Transactional(readOnly = true)
    List<StaffView> listStaff(CurrentUser user, UUID teamId) {
        UUID tenantId = Rules.tenant();
        requireVisible(tenantId, teamId, user);
        return teams.listStaff(tenantId, teamId).stream()
                .map(s -> new StaffView(s.userId(), s.name(), s.email(), s.role()))
                .toList();
    }

    @Transactional
    List<StaffView> addStaff(CurrentUser user, UUID teamId, UUID userId, String role) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        requireVisible(tenantId, teamId, user);
        if (!teams.activeMembershipExists(tenantId, userId)) throw Rules.notFound("Utilizador sem papel ativo no clube");
        String normalizedRole = validStaffRole(role);
        if (teams.staffExists(tenantId, teamId, userId)) throw Rules.conflict("Este utilizador já é staff da equipa");
        try {
            teams.insertStaff(tenantId, teamId, userId, normalizedRole, user.id());
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Este utilizador já é staff da equipa");
        }
        audit.record(tenantId, user.id(), "TEAM_STAFF_ADDED", "team", teamId);
        return listStaff(user, teamId);
    }

    @Transactional
    void removeStaff(CurrentUser user, UUID teamId, UUID userId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        requireVisible(tenantId, teamId, user);
        if (!teams.deleteStaff(tenantId, teamId, userId)) throw Rules.notFound("Utilizador não é staff da equipa");
        audit.record(tenantId, user.id(), "TEAM_STAFF_REMOVED", "team", teamId);
    }

    // --- plantel ---

    @Transactional(readOnly = true)
    List<RosterView> listAthletes(CurrentUser user, UUID teamId) {
        UUID tenantId = Rules.tenant();
        requireVisible(tenantId, teamId, user);
        return teams.listAthletes(tenantId, teamId).stream()
                .map(a -> new RosterView(a.athleteId(), a.fullName(), a.status()))
                .toList();
    }

    @Transactional
    List<RosterView> addAthleteToTeam(CurrentUser user, UUID teamId, UUID athleteId,
                                      UUID registrationAgeGroupId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        requireVisible(tenantId, teamId, user);
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
        teams.saveRegistration(tenantId, season.id(), athleteId, registrationGroup.id(), user.id());
        try {
            teams.insertAthlete(tenantId, teamId, athleteId, user.id());
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Este atleta já pertence à equipa");
        }

        audit.record(tenantId, user.id(), "TEAM_ATHLETE_ADDED", "team", teamId);
        return listAthletes(user, teamId);
    }

    private AgeGroupRepository.AgeGroupRow resolveRegistrationGroup(
            UUID tenantId, SeasonRepository.SeasonRow season, UUID athleteId, LocalDate birthDate,
            UUID requestedGroupId) {
        List<AgeGroupRepository.AgeGroupRow> groups = ageGroups.list(tenantId);
        int age = AgeGroupEligibility.ageAt(birthDate, season);
        if (requestedGroupId == null && (age >= 19 && age <= 21 || age > 35))
            throw Rules.badRequest("É obrigatório indicar o escalão de inscrição para este atleta");
        AgeGroupRepository.AgeGroupRow selected = requestedGroupId == null
                ? groups.stream().filter(g -> AgeGroupEligibility.eligible(age, g))
                    .findFirst()
                    .or(() -> groups.stream().filter(g -> promotions.activeExists(
                            tenantId, season.id(), g.id(), athleteId)).findFirst())
                    .orElseThrow(() -> Rules.badRequest("Não foi possível determinar o escalão de inscrição"))
                : groups.stream().filter(g -> g.id().equals(requestedGroupId)).findFirst()
                    .orElseThrow(() -> Rules.notFound("Escalão de inscrição não encontrado"));
        AgeGroupEligibility.require(age, selected,
                promotions.activeExists(tenantId, season.id(), selected.id(), athleteId), "inscrição na época");
        return selected;
    }

    @Transactional
    void removeAthlete(CurrentUser user, UUID teamId, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        requireVisible(tenantId, teamId, user);
        if (!teams.deleteAthlete(tenantId, teamId, athleteId)) throw Rules.notFound("Atleta não pertence à equipa");
        audit.record(tenantId, user.id(), "TEAM_ATHLETE_REMOVED", "team", teamId);
    }

    // --- auxiliares ---

    /**
     * Regra federativa FPV aplicada ao escalão da equipa: o atleta só pode integrar o plantel quando
     * a idade na data de referência da época está dentro dos limites do escalão. Atletas mais novos
     * do que o escalão (subida) dependem de autorização excepcional para a época; atletas mais velhos
     * do que o escalão não podem jogar em escalão inferior.
     */
    private void validarRegrasFpV(UUID tenantId, SeasonRepository.SeasonRow season,
                                 AgeGroupRepository.AgeGroupRow ageGroup, UUID athleteId, LocalDate birthDate) {
        AgeGroupEligibility.require(AgeGroupEligibility.ageAt(birthDate, season), ageGroup,
                promotions.activeExists(tenantId, season.id(), ageGroup.id(), athleteId), "plantel da equipa");
    }

    private TeamRepository.TeamRow requireVisible(UUID tenantId, UUID teamId, CurrentUser user) {
        if (user.isManager()) return teams.findById(tenantId, teamId).orElseThrow(TeamService::notFound);
        if (user.has(Role.TREINADOR)) return teams.findVisible(tenantId, teamId, user.id(), false).orElseThrow(TeamService::notFound);
        throw Rules.forbidden();
    }

    /** A época e o escalão têm de existir no clube e não podem estar anonimizados. */
    private void requireReferences(UUID tenantId, UUID seasonId, UUID ageGroupId) {
        if (seasonId != null && !teams.seasonExists(tenantId, seasonId)) throw Rules.notFound("Época não encontrada");
        if (ageGroupId != null && !teams.ageGroupExists(tenantId, ageGroupId)) throw Rules.notFound("Escalão não encontrado");
    }

    private static String validStaffRole(String role) {
        return Rules.parse(StaffRole.class, role, "Papel de staff", StaffRole.DEFAULT).name();
    }

    private static ResponseStatusException notFound() {
        return Rules.notFound("Equipa não encontrada");
    }

    private static TeamView view(TeamRepository.TeamRow row) {
        return new TeamView(row.id(), row.name(), row.status(), row.seasonId(), row.ageGroupId(),
                row.createdAt(), row.updatedAt());
    }
}
