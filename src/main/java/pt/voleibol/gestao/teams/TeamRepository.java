package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import pt.voleibol.gestao.shared.Db;

/**
 * Acesso a dados das equipas, do staff e do plantel. As equipas podem ser associadas a uma época
 * e a um escalão do clube.
 */
@Repository
class TeamRepository {

    record TeamRow(UUID id, String name, String status, UUID seasonId, UUID ageGroupId,
                   Instant createdAt, Instant updatedAt) {
    }

    record StaffRow(UUID userId, String name, String email, String role) {
    }

    record RosterRow(UUID athleteId, String fullName, String status) {
    }

    private static final String SELECT = """
            SELECT t.id, t.name, t.status, t.season_id, t.age_group_id, t.created_at, t.updated_at
            FROM team t
            """;

    private final JdbcClient jdbc;

    TeamRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<TeamRow> list(UUID tenantId, UUID actorId, boolean manager) {
        String sql = SELECT + " WHERE t.tenant_id = :tenant AND t.anonymized_at IS NULL"
                + (manager ? "" : """
                         AND EXISTS (
                            SELECT 1 FROM team_staff s
                            WHERE s.tenant_id = t.tenant_id AND s.team_id = t.id AND s.user_id = :actor)
                        """)
                + " ORDER BY t.name, t.id";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenant", tenantId);
        if (!manager) spec = spec.param("actor", actorId);
        return spec.query(TeamRepository::map).list();
    }

    Optional<TeamRow> findVisible(UUID tenantId, UUID teamId, UUID actorId, boolean manager) {
        String sql = SELECT + " WHERE t.tenant_id = :tenant AND t.id = :id AND t.anonymized_at IS NULL"
                + (manager ? "" : """
                         AND EXISTS (
                            SELECT 1 FROM team_staff s
                            WHERE s.tenant_id = t.tenant_id AND s.team_id = t.id AND s.user_id = :actor)
                        """);
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenant", tenantId).param("id", teamId);
        if (!manager) spec = spec.param("actor", actorId);
        return spec.query(TeamRepository::map).optional();
    }

    Optional<TeamRow> findById(UUID tenantId, UUID teamId) {
        return jdbc.sql(SELECT + " WHERE t.tenant_id = :tenant AND t.id = :id AND t.anonymized_at IS NULL")
                .param("tenant", tenantId).param("id", teamId)
                .query(TeamRepository::map)
                .optional();
    }

    boolean nameExists(UUID tenantId, String name, UUID excludeId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM team WHERE tenant_id = :tenant "
                + "AND anonymized_at IS NULL AND lower(name) = lower(:name)";
        JdbcClient.StatementSpec spec = jdbc.sql(excludeId == null ? sql + ")"
                        : sql + " AND id <> :exclude)")
                .param("tenant", tenantId).param("name", name);
        if (excludeId != null) spec = spec.param("exclude", excludeId);
        return Db.exists(spec);
    }

    void insert(UUID id, UUID tenantId, String name, String status, UUID seasonId, UUID ageGroupId,
                UUID actorId) {
        jdbc.sql("""
                        INSERT INTO team (id, tenant_id, name, status, season_id, age_group_id, created_by)
                        VALUES (:id, :tenant, :name, :status, :season, :age_group, :actor)
                        """)
                .param("id", id).param("tenant", tenantId).param("name", name).param("status", status)
                .param("season", seasonId).param("age_group", ageGroupId).param("actor", actorId)
                .update();
    }

    boolean update(UUID tenantId, UUID teamId, String name, String status, UUID seasonId, UUID ageGroupId) {
        return jdbc.sql("""
                        UPDATE team
                        SET name = :name, status = :status, season_id = :season, age_group_id = :age_group,
                            updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId).param("id", teamId).param("name", name).param("status", status)
                .param("season", seasonId).param("age_group", ageGroupId)
                .update() == 1;
    }

    /**
     * Remoção não destrutiva: desativa e anonimiza a equipa, preservando staff e plantel para
     * histórico. O nome passa a um marcador anonimizado e pode ser reutilizado.
     */
    boolean anonymize(UUID tenantId, UUID teamId) {
        return jdbc.sql("""
                        UPDATE team
                        SET status = 'INACTIVE', anonymized_at = now(), name = 'Equipa anonimizada',
                            updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId).param("id", teamId)
                .update() == 1;
    }

    // --- staff ---

    List<StaffRow> listStaff(UUID tenantId, UUID teamId) {
        return jdbc.sql("""
                        SELECT s.user_id, u.name, u.email, s.role
                        FROM team_staff s
                        JOIN app_user u ON u.id = s.user_id
                        WHERE s.tenant_id = :tenant AND s.team_id = :team
                        ORDER BY u.name, u.id
                        """)
                .param("tenant", tenantId).param("team", teamId)
                .query((rs, i) -> new StaffRow(Db.uuid(rs, "user_id"), rs.getString("name"), rs.getString("email"),
                        rs.getString("role")))
                .list();
    }

    boolean staffExists(UUID tenantId, UUID teamId, UUID userId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM team_staff
                            WHERE tenant_id = :tenant AND team_id = :team AND user_id = :user)
                        """)
                .param("tenant", tenantId).param("team", teamId).param("user", userId));
    }

    void insertStaff(UUID tenantId, UUID teamId, UUID userId, String role, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO team_staff (tenant_id, team_id, user_id, role, created_by)
                        VALUES (:tenant, :team, :user, :role, :actor)
                        """)
                .param("tenant", tenantId).param("team", teamId).param("user", userId).param("role", role)
                .param("actor", actorId)
                .update();
    }

    boolean deleteStaff(UUID tenantId, UUID teamId, UUID userId) {
        return jdbc.sql("""
                        DELETE FROM team_staff
                        WHERE tenant_id = :tenant AND team_id = :team AND user_id = :user
                        """)
                .param("tenant", tenantId).param("team", teamId).param("user", userId)
                .update() == 1;
    }

    // --- plantel ---

    List<RosterRow> listAthletes(UUID tenantId, UUID teamId) {
        return jdbc.sql("""
                        SELECT a.id, a.full_name, a.status
                        FROM team_athlete ta
                        JOIN athlete a ON a.tenant_id = ta.tenant_id AND a.id = ta.athlete_id
                        WHERE ta.tenant_id = :tenant AND ta.team_id = :team
                          AND a.anonymized_at IS NULL
                        ORDER BY a.full_name, a.id
                        """)
                .param("tenant", tenantId).param("team", teamId)
                .query((rs, i) -> new RosterRow(Db.uuid(rs, "id"), rs.getString("full_name"), rs.getString("status")))
                .list();
    }

    boolean rosterExists(UUID tenantId, UUID teamId, UUID athleteId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM team_athlete
                            WHERE tenant_id = :tenant AND team_id = :team AND athlete_id = :athlete)
                        """)
                .param("tenant", tenantId).param("team", teamId).param("athlete", athleteId));
    }

    void insertAthlete(UUID tenantId, UUID teamId, UUID athleteId, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO team_athlete (tenant_id, team_id, athlete_id, created_by)
                        VALUES (:tenant, :team, :athlete, :actor)
                        """)
                .param("tenant", tenantId).param("team", teamId).param("athlete", athleteId)
                .param("actor", actorId)
                .update();
    }

    void saveRegistration(UUID tenantId, UUID seasonId, UUID athleteId, UUID ageGroupId, UUID actorId) {
        jdbc.sql("""
                INSERT INTO athlete_season_registration
                    (tenant_id, season_id, athlete_id, age_group_id, created_by)
                VALUES (:tenant, :season, :athlete, :group, :actor)
                ON CONFLICT (tenant_id, season_id, athlete_id)
                DO UPDATE SET age_group_id = EXCLUDED.age_group_id, updated_at = now()
                """)
                .param("tenant", tenantId).param("season", seasonId).param("athlete", athleteId)
                .param("group", ageGroupId).param("actor", actorId)
                .update();
    }

    boolean deleteAthlete(UUID tenantId, UUID teamId, UUID athleteId) {
        return jdbc.sql("""
                        DELETE FROM team_athlete
                        WHERE tenant_id = :tenant AND team_id = :team AND athlete_id = :athlete
                        """)
                .param("tenant", tenantId).param("team", teamId).param("athlete", athleteId)
                .update() == 1;
    }

    // --- validações ---

    boolean activeMembershipExists(UUID tenantId, UUID userId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM membership
                            WHERE tenant_id = :tenant AND user_id = :user AND status = 'ACTIVE')
                        """)
                .param("tenant", tenantId).param("user", userId));
    }

    boolean athleteExists(UUID tenantId, UUID athleteId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM athlete
                            WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL)
                        """)
                .param("tenant", tenantId).param("id", athleteId));
    }

    boolean seasonExists(UUID tenantId, UUID seasonId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM season
                            WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL)
                        """)
                .param("tenant", tenantId).param("id", seasonId));
    }

    boolean ageGroupExists(UUID tenantId, UUID ageGroupId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM age_group
                            WHERE id = :id AND is_active)
                        """)
                .param("id", ageGroupId));
    }

    LocalDate birthDate(UUID tenantId, UUID athleteId) {
        return jdbc.sql("""
                        SELECT birth_date
                        FROM athlete
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId).param("id", athleteId)
                .query((rs, i) -> Db.date(rs, "birth_date"))
                .single();
    }

    // --- reavaliação da regra federativa ---

    /**
     * Inscrição na época com a época, o escalão e a data de nascimento já resolvidos, para que a
     * regra federativa possa ser reavaliada depois de uma alteração.
     */
    record EligibilityRow(UUID athleteId, String athleteName, String context, LocalDate birthDate,
                          SeasonRepository.SeasonRow season, AgeGroupRepository.AgeGroupRow ageGroup) {
    }

    private static final String REGISTRATIONS = """
            SELECT r.athlete_id, a.full_name, a.birth_date,
                   s.id AS season_id, s.name AS season_name, s.start_date, s.end_date, s.cutoff_date,
                   s.is_active AS season_active, s.created_at AS season_created_at,
                   s.updated_at AS season_updated_at,
                   g.id AS group_id, g.name AS group_name, g.min_age, g.max_age,
                   g.is_active AS group_active, g.created_at AS group_created_at,
                   g.updated_at AS group_updated_at
            FROM athlete_season_registration r
            JOIN athlete a ON a.tenant_id = r.tenant_id AND a.id = r.athlete_id
            JOIN season s ON s.tenant_id = r.tenant_id AND s.id = r.season_id
            JOIN age_group g ON g.id = r.age_group_id
            WHERE r.tenant_id = :tenant AND a.anonymized_at IS NULL AND s.anonymized_at IS NULL
              AND g.is_active
            """;

    private static final String ROSTER = """
            SELECT ta.athlete_id, a.full_name, a.birth_date,
                   s.id AS season_id, s.name AS season_name, s.start_date, s.end_date, s.cutoff_date,
                   s.is_active AS season_active, s.created_at AS season_created_at,
                   s.updated_at AS season_updated_at,
                   g.id AS group_id, g.name AS group_name, g.min_age, g.max_age,
                   g.is_active AS group_active, g.created_at AS group_created_at,
                   g.updated_at AS group_updated_at
            FROM team_athlete ta
            JOIN team t ON t.tenant_id = ta.tenant_id AND t.id = ta.team_id
            JOIN athlete a ON a.tenant_id = ta.tenant_id AND a.id = ta.athlete_id
            JOIN season s ON s.tenant_id = t.tenant_id AND s.id = t.season_id
            JOIN age_group g ON g.id = t.age_group_id
            WHERE ta.tenant_id = :tenant AND t.anonymized_at IS NULL AND a.anonymized_at IS NULL
              AND s.anonymized_at IS NULL AND g.is_active
            """;

    List<EligibilityRow> registrationsOfAthlete(UUID tenantId, UUID athleteId) {
        return jdbc.sql(REGISTRATIONS + " AND r.athlete_id = :athlete ORDER BY s.name, g.min_age")
                .param("tenant", tenantId).param("athlete", athleteId)
                .query((rs, i) -> mapEligibility(rs, "inscrição na época")).list();
    }

    List<EligibilityRow> registrationsOfSeason(UUID tenantId, UUID seasonId) {
        return jdbc.sql(REGISTRATIONS + " AND r.season_id = :season ORDER BY a.full_name, g.min_age")
                .param("tenant", tenantId).param("season", seasonId)
                .query((rs, i) -> mapEligibility(rs, "inscrição na época")).list();
    }

    List<EligibilityRow> rosterOfAthlete(UUID tenantId, UUID athleteId) {
        return jdbc.sql(ROSTER + " AND ta.athlete_id = :athlete ORDER BY s.name, g.min_age")
                .param("tenant", tenantId).param("athlete", athleteId)
                .query((rs, i) -> mapEligibility(rs, "plantel da equipa")).list();
    }

    List<EligibilityRow> rosterOfSeason(UUID tenantId, UUID seasonId) {
        return jdbc.sql(ROSTER + " AND s.id = :season ORDER BY a.full_name, g.min_age")
                .param("tenant", tenantId).param("season", seasonId)
                .query((rs, i) -> mapEligibility(rs, "plantel da equipa")).list();
    }

    List<EligibilityRow> rosterOfTeam(UUID tenantId, UUID teamId) {
        return jdbc.sql(ROSTER + " AND ta.team_id = :team ORDER BY a.full_name, g.min_age")
                .param("tenant", tenantId).param("team", teamId)
                .query((rs, i) -> mapEligibility(rs, "plantel da equipa")).list();
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID teamId) {
        Db.audit(jdbc, tenantId, actorId, action, "team", teamId);
    }

    private static TeamRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TeamRow(Db.uuid(rs, "id"), rs.getString("name"), rs.getString("status"), Db.uuid(rs, "season_id"),
                Db.uuid(rs, "age_group_id"), Db.instant(rs, "created_at"),
                Db.instant(rs, "updated_at"));
    }

    private static EligibilityRow mapEligibility(java.sql.ResultSet rs, String context) throws java.sql.SQLException {
        return new EligibilityRow(Db.uuid(rs, "athlete_id"), rs.getString("full_name"), context,
                Db.date(rs, "birth_date"),
                new SeasonRepository.SeasonRow(Db.uuid(rs, "season_id"), rs.getString("season_name"),
                        Db.date(rs, "start_date"), Db.date(rs, "end_date"), Db.date(rs, "cutoff_date"),
                        rs.getBoolean("season_active"), Db.instant(rs, "season_created_at"),
                        Db.instant(rs, "season_updated_at")),
                new AgeGroupRepository.AgeGroupRow(Db.uuid(rs, "group_id"), rs.getString("group_name"),
                        rs.getInt("min_age"), (Integer) rs.getObject("max_age"), rs.getBoolean("group_active"),
                        Db.instant(rs, "group_created_at"), Db.instant(rs, "group_updated_at")));
    }
}
