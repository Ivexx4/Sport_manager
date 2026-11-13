package pt.voleibol.gestao.athletes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acesso a dados dos atletas. Todas as leituras passam pela política de visibilidade do ator
 * (perfil no clube e relações com o atleta), além do RLS do tenant.
 */
@Repository
class AthleteRepository {

    record AthleteRow(UUID id, String fullName, LocalDate birthDate, String gender, String nif,
                      String email, String phone, String licenseNumber, String status, UUID userId,
                      Instant createdAt, Instant updatedAt) {
    }

    record Data(String fullName, LocalDate birthDate, String gender, String nif, String email,
                String phone, String licenseNumber, String status, UUID userId) {
    }

    record GuardianRow(UUID userId, String name, String email, String relationship) {
    }

    /**
     * Visibilidade do atleta para o ator. Com {@code writable=true} o papel FINANCEIRO deixa de ver
     * tudo: só pode ler, por isso não entra no âmbito de escrita.
     */
    private static final String SCOPE = """
            (
                :all = TRUE
                OR (:trainer = TRUE AND EXISTS (
                        SELECT 1 FROM team_athlete ta
                        JOIN team_staff ts
                          ON ts.tenant_id = ta.tenant_id AND ts.team_id = ta.team_id
                        WHERE ta.tenant_id = a.tenant_id
                          AND ta.athlete_id = a.id
                          AND ts.user_id = :actor))
                OR (:self = TRUE AND a.user_id = :actor)
                OR (:guardian = TRUE AND EXISTS (
                        SELECT 1 FROM athlete_guardian g
                        WHERE g.tenant_id = a.tenant_id
                          AND g.athlete_id = a.id
                          AND g.user_id = :actor))
            )
            """;

    private static final String SELECT = """
            SELECT a.id, a.full_name, a.birth_date, a.gender, a.nif, a.email, a.phone,
                   a.license_number, a.status, a.user_id, a.created_at, a.updated_at
            FROM athlete a
            """;

    private final JdbcClient jdbc;

    AthleteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AthleteRow> findVisible(UUID tenantId, UUID athleteId, UUID actorId, Set<String> roles) {
        return scoped(jdbc.sql(SELECT + " WHERE a.tenant_id = :tenant AND a.id = :id AND " + SCOPE), tenantId,
                athleteId, actorId, roles, false)
                .query(AthleteRepository::map)
                .optional();
    }

    Optional<AthleteRow> findWritable(UUID tenantId, UUID athleteId, UUID actorId, Set<String> roles) {
        return scoped(jdbc.sql(SELECT + " WHERE a.tenant_id = :tenant AND a.id = :id AND " + SCOPE), tenantId,
                athleteId, actorId, roles, true)
                .query(AthleteRepository::map)
                .optional();
    }

    List<AthleteRow> list(UUID tenantId, UUID actorId, Set<String> roles, String query, String status,
                          UUID teamId, int limit, long offset) {
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" WHERE a.tenant_id = :tenant AND ").append(SCOPE);
        if (query != null && !query.isBlank()) {
            sql.append(" AND a.full_name ILIKE '%' || :q || '%'");
        }
        if (status != null) {
            sql.append(" AND a.status = :status");
        }
        if (teamId != null) {
            sql.append("""
                     AND EXISTS (
                        SELECT 1 FROM team_athlete ta
                        WHERE ta.tenant_id = a.tenant_id AND ta.athlete_id = a.id
                          AND ta.team_id = :teamId)
                    """);
        }
        sql.append(" ORDER BY a.full_name, a.id LIMIT :limit OFFSET :offset");

        JdbcClient.StatementSpec spec = scoped(jdbc.sql(sql.toString()), tenantId, null, actorId, roles, false);
        if (query != null && !query.isBlank()) {
            spec = spec.param("q", query.trim());
        }
        if (status != null) {
            spec = spec.param("status", status);
        }
        if (teamId != null) {
            spec = spec.param("teamId", teamId);
        }
        return spec.param("limit", limit).param("offset", offset).query(AthleteRepository::map).list();
    }

    long count(UUID tenantId, UUID actorId, Set<String> roles, String query, String status, UUID teamId) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM athlete a WHERE a.tenant_id = :tenant AND ")
                .append(SCOPE);
        if (query != null && !query.isBlank()) {
            sql.append(" AND a.full_name ILIKE '%' || :q || '%'");
        }
        if (status != null) {
            sql.append(" AND a.status = :status");
        }
        if (teamId != null) {
            sql.append("""
                     AND EXISTS (
                        SELECT 1 FROM team_athlete ta
                        WHERE ta.tenant_id = a.tenant_id AND ta.athlete_id = a.id
                          AND ta.team_id = :teamId)
                    """);
        }
        JdbcClient.StatementSpec spec = scoped(jdbc.sql(sql.toString()), tenantId, null, actorId, roles, false);
        if (query != null && !query.isBlank()) {
            spec = spec.param("q", query.trim());
        }
        if (status != null) {
            spec = spec.param("status", status);
        }
        if (teamId != null) {
            spec = spec.param("teamId", teamId);
        }
        return spec.query(Long.class).single();
    }

    private static JdbcClient.StatementSpec scoped(JdbcClient.StatementSpec spec, UUID tenantId,
                                                   UUID athleteId, UUID actorId, Set<String> roles,
                                                   boolean writable) {
        boolean manager = roles.contains("ADMIN") || roles.contains("DIRECAO");
        boolean all = writable ? manager : manager || roles.contains("FINANCEIRO");
        JdbcClient.StatementSpec result = spec.param("tenant", tenantId)
                .param("all", all)
                .param("trainer", roles.contains("TREINADOR"))
                .param("self", roles.contains("ATLETA"))
                .param("guardian", roles.contains("ENCARREGADO"))
                .param("actor", actorId);
        return athleteId == null ? result : result.param("id", athleteId);
    }

    // --- escrita ---

    void insert(UUID id, UUID tenantId, Data data, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO athlete (id, tenant_id, full_name, birth_date, gender, nif, email, phone,
                                             license_number, status, user_id, created_by)
                        VALUES (:id, :tenant, :name, :birth, :gender, :nif, :email, :phone,
                                :license, :status, :user, :actor)
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("name", data.fullName())
                .param("birth", data.birthDate())
                .param("gender", data.gender())
                .param("nif", data.nif())
                .param("email", data.email())
                .param("phone", data.phone())
                .param("license", data.licenseNumber())
                .param("status", data.status())
                .param("user", data.userId())
                .param("actor", actorId)
                .update();
    }

    boolean update(UUID tenantId, UUID athleteId, Data data) {
        return jdbc.sql("""
                        UPDATE athlete
                        SET full_name = :name, birth_date = :birth, gender = :gender, nif = :nif,
                            email = :email, phone = :phone, license_number = :license, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenantId)
                .param("id", athleteId)
                .param("name", data.fullName())
                .param("birth", data.birthDate())
                .param("gender", data.gender())
                .param("nif", data.nif())
                .param("email", data.email())
                .param("phone", data.phone())
                .param("license", data.licenseNumber())
                .update() == 1;
    }

    boolean linkUser(UUID tenantId, UUID athleteId, UUID userId) {
        return jdbc.sql("""
                        UPDATE athlete SET user_id = :user, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenantId)
                .param("id", athleteId)
                .param("user", userId)
                .update() == 1;
    }

    boolean unlinkUser(UUID tenantId, UUID athleteId) {
        return jdbc.sql("""
                        UPDATE athlete SET user_id = NULL, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenantId)
                .param("id", athleteId)
                .update() == 1;
    }

    boolean updateStatus(UUID tenantId, UUID athleteId, String status) {
        return jdbc.sql("""
                        UPDATE athlete SET status = :status, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenantId)
                .param("id", athleteId)
                .param("status", status)
                .update() == 1;
    }

    boolean delete(UUID tenantId, UUID athleteId) {
        return jdbc.sql("DELETE FROM athlete WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId)
                .param("id", athleteId)
                .update() == 1;
    }

    // --- validações ---

    boolean licenseExists(UUID tenantId, String licenseNumber, UUID excludeId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM athlete WHERE tenant_id = :tenant "
                + "AND lower(license_number) = lower(:license)";
        JdbcClient.StatementSpec spec = jdbc.sql(excludeId == null ? sql + ")"
                        : sql + " AND id <> :exclude)")
                .param("tenant", tenantId)
                .param("license", licenseNumber);
        if (excludeId != null) {
            spec = spec.param("exclude", excludeId);
        }
        return spec.query(Boolean.class).single();
    }

    boolean userLinked(UUID tenantId, UUID userId, UUID excludeId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM athlete WHERE tenant_id = :tenant AND user_id = :user";
        JdbcClient.StatementSpec spec = jdbc.sql(excludeId == null ? sql + ")"
                        : sql + " AND id <> :exclude)")
                .param("tenant", tenantId)
                .param("user", userId);
        if (excludeId != null) {
            spec = spec.param("exclude", excludeId);
        }
        return spec.query(Boolean.class).single();
    }

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

    // --- encarregados ---

    List<GuardianRow> listGuardians(UUID tenantId, UUID athleteId) {
        return jdbc.sql("""
                        SELECT g.user_id, u.name, u.email, g.relationship
                        FROM athlete_guardian g
                        JOIN app_user u ON u.id = g.user_id
                        WHERE g.tenant_id = :tenant AND g.athlete_id = :athlete
                        ORDER BY u.name, u.id
                        """)
                .param("tenant", tenantId)
                .param("athlete", athleteId)
                .query((rs, i) -> new GuardianRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("relationship")))
                .list();
    }

    boolean guardianExists(UUID tenantId, UUID athleteId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM athlete_guardian
                            WHERE tenant_id = :tenant AND athlete_id = :athlete AND user_id = :user)
                        """)
                .param("tenant", tenantId)
                .param("athlete", athleteId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    void insertGuardian(UUID tenantId, UUID athleteId, UUID userId, String relationship, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO athlete_guardian (tenant_id, athlete_id, user_id, relationship, created_by)
                        VALUES (:tenant, :athlete, :user, :relationship, :actor)
                        """)
                .param("tenant", tenantId)
                .param("athlete", athleteId)
                .param("user", userId)
                .param("relationship", relationship)
                .param("actor", actorId)
                .update();
    }

    boolean deleteGuardian(UUID tenantId, UUID athleteId, UUID userId) {
        return jdbc.sql("""
                        DELETE FROM athlete_guardian
                        WHERE tenant_id = :tenant AND athlete_id = :athlete AND user_id = :user
                        """)
                .param("tenant", tenantId)
                .param("athlete", athleteId)
                .param("user", userId)
                .update() == 1;
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID athleteId) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)
                        VALUES (:tenant, :actor, :action, 'athlete', :entity)
                        """)
                .param("tenant", tenantId)
                .param("actor", actorId)
                .param("action", action)
                .param("entity", athleteId)
                .update();
    }

    private static AthleteRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AthleteRow(
                rs.getObject("id", UUID.class),
                rs.getString("full_name"),
                rs.getObject("birth_date", LocalDate.class),
                rs.getString("gender"),
                rs.getString("nif"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("license_number"),
                rs.getString("status"),
                rs.getObject("user_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
