package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acesso a dados da base mínima de equipas. O módulo teams alargará esta base com épocas,
 * escalões, plantéis e staff próprio; as relações criadas aqui mantêm-se.
 */
@Repository
class TeamRepository {

    record TeamRow(UUID id, String name, String status, Instant createdAt, Instant updatedAt) {
    }

    record StaffRow(UUID userId, String name, String email, String role) {
    }

    record RosterRow(UUID athleteId, String fullName, String status) {
    }

    private static final String SELECT = """
            SELECT t.id, t.name, t.status, t.created_at, t.updated_at
            FROM team t
            """;

    private final JdbcClient jdbc;

    TeamRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<TeamRow> list(UUID tenantId, UUID actorId, boolean manager) {
        String sql = SELECT + " WHERE t.tenant_id = :tenant"
                + (manager ? "" : """
                         AND EXISTS (
                            SELECT 1 FROM team_staff s
                            WHERE s.tenant_id = t.tenant_id AND s.team_id = t.id AND s.user_id = :actor)
                        """)
                + " ORDER BY t.name, t.id";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenant", tenantId);
        if (!manager) {
            spec = spec.param("actor", actorId);
        }
        return spec.query(TeamRepository::map).list();
    }

    Optional<TeamRow> findVisible(UUID tenantId, UUID teamId, UUID actorId, boolean manager) {
        String sql = SELECT + " WHERE t.tenant_id = :tenant AND t.id = :id"
                + (manager ? "" : """
                         AND EXISTS (
                            SELECT 1 FROM team_staff s
                            WHERE s.tenant_id = t.tenant_id AND s.team_id = t.id AND s.user_id = :actor)
                        """);
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenant", tenantId).param("id", teamId);
        if (!manager) {
            spec = spec.param("actor", actorId);
        }
        return spec.query(TeamRepository::map).optional();
    }

    Optional<TeamRow> findById(UUID tenantId, UUID teamId) {
        return jdbc.sql(SELECT + " WHERE t.tenant_id = :tenant AND t.id = :id")
                .param("tenant", tenantId)
                .param("id", teamId)
                .query(TeamRepository::map)
                .optional();
    }

    boolean nameExists(UUID tenantId, String name, UUID excludeId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM team WHERE tenant_id = :tenant AND lower(name) = lower(:name)";
        JdbcClient.StatementSpec spec = jdbc.sql(excludeId == null ? sql + ")"
                        : sql + " AND id <> :exclude)")
                .param("tenant", tenantId)
                .param("name", name);
        if (excludeId != null) {
            spec = spec.param("exclude", excludeId);
        }
        return spec.query(Boolean.class).single();
    }

    void insert(UUID id, UUID tenantId, String name, String status, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO team (id, tenant_id, name, status, created_by)
                        VALUES (:id, :tenant, :name, :status, :actor)
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("name", name)
                .param("status", status)
                .param("actor", actorId)
                .update();
    }

    boolean update(UUID tenantId, UUID teamId, String name, String status) {
        return jdbc.sql("""
                        UPDATE team SET name = :name, status = :status, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenantId)
                .param("id", teamId)
                .param("name", name)
                .param("status", status)
                .update() == 1;
    }

    boolean delete(UUID tenantId, UUID teamId) {
        return jdbc.sql("DELETE FROM team WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId)
                .param("id", teamId)
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
                .param("tenant", tenantId)
                .param("team", teamId)
                .query((rs, i) -> new StaffRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("role")))
                .list();
    }

    boolean staffExists(UUID tenantId, UUID teamId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM team_staff
                            WHERE tenant_id = :tenant AND team_id = :team AND user_id = :user)
                        """)
                .param("tenant", tenantId)
                .param("team", teamId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    void insertStaff(UUID tenantId, UUID teamId, UUID userId, String role, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO team_staff (tenant_id, team_id, user_id, role, created_by)
                        VALUES (:tenant, :team, :user, :role, :actor)
                        """)
                .param("tenant", tenantId)
                .param("team", teamId)
                .param("user", userId)
                .param("role", role)
                .param("actor", actorId)
                .update();
    }

    boolean deleteStaff(UUID tenantId, UUID teamId, UUID userId) {
        return jdbc.sql("""
                        DELETE FROM team_staff
                        WHERE tenant_id = :tenant AND team_id = :team AND user_id = :user
                        """)
                .param("tenant", tenantId)
                .param("team", teamId)
                .param("user", userId)
                .update() == 1;
    }

    // --- plantel ---

    List<RosterRow> listAthletes(UUID tenantId, UUID teamId) {
        return jdbc.sql("""
                        SELECT a.id, a.full_name, a.status
                        FROM team_athlete ta
                        JOIN athlete a ON a.tenant_id = ta.tenant_id AND a.id = ta.athlete_id
                        WHERE ta.tenant_id = :tenant AND ta.team_id = :team
                        ORDER BY a.full_name, a.id
                        """)
                .param("tenant", tenantId)
                .param("team", teamId)
                .query((rs, i) -> new RosterRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("status")))
                .list();
    }

    boolean rosterExists(UUID tenantId, UUID teamId, UUID athleteId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM team_athlete
                            WHERE tenant_id = :tenant AND team_id = :team AND athlete_id = :athlete)
                        """)
                .param("tenant", tenantId)
                .param("team", teamId)
                .param("athlete", athleteId)
                .query(Boolean.class)
                .single();
    }

    void insertAthlete(UUID tenantId, UUID teamId, UUID athleteId, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO team_athlete (tenant_id, team_id, athlete_id, created_by)
                        VALUES (:tenant, :team, :athlete, :actor)
                        """)
                .param("tenant", tenantId)
                .param("team", teamId)
                .param("athlete", athleteId)
                .param("actor", actorId)
                .update();
    }

    boolean deleteAthlete(UUID tenantId, UUID teamId, UUID athleteId) {
        return jdbc.sql("""
                        DELETE FROM team_athlete
                        WHERE tenant_id = :tenant AND team_id = :team AND athlete_id = :athlete
                        """)
                .param("tenant", tenantId)
                .param("team", teamId)
                .param("athlete", athleteId)
                .update() == 1;
    }

    // --- validações ---

    boolean activeMembershipExists(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM membership
                            WHERE tenant_id = :tenant AND user_id = :user AND status = 'ACTIVE')
                        """)
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    boolean athleteExists(UUID tenantId, UUID athleteId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM athlete WHERE tenant_id = :tenant AND id = :id)")
                .param("tenant", tenantId)
                .param("id", athleteId)
                .query(Boolean.class)
                .single();
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID teamId) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)
                        VALUES (:tenant, :actor, :action, 'team', :entity)
                        """)
                .param("tenant", tenantId)
                .param("actor", actorId)
                .param("action", action)
                .param("entity", teamId)
                .update();
    }

    private static TeamRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TeamRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
