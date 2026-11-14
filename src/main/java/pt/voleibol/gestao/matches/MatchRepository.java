package pt.voleibol.gestao.matches;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class MatchRepository {

    record MatchRow(UUID id, UUID teamId, String competition, String opponent, String location, UUID courtId,
                    Instant matchDate, boolean isHome, String status, Instant createdAt, Instant updatedAt) {
    }

    record SetScoreRow(int setNumber, int ourPoints, int opponentPoints) {
    }

    record CallUpRow(UUID athleteId, String fullName, String status, String response, Instant respondedAt) {
    }

    private final JdbcClient jdbc;

    MatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<MatchRow> list(UUID tenantId, UUID teamId) {
        String sql = """
                SELECT id, team_id, competition, opponent, location, court_id, match_date, is_home, status,
                       created_at, updated_at
                FROM match
                WHERE tenant_id = :tenant AND anonymized_at IS NULL
                """;
        JdbcClient.StatementSpec spec;
        if (teamId != null) {
            sql = sql + " AND team_id = :team";
            spec = jdbc.sql(sql).param("tenant", tenantId).param("team", teamId);
        } else {
            spec = jdbc.sql(sql).param("tenant", tenantId);
        }
        return spec.query(MatchRepository::mapMatch).list();
    }

    Optional<MatchRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        SELECT id, team_id, competition, opponent, location, court_id, match_date, is_home, status,
                               created_at, updated_at
                        FROM match
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .query(MatchRepository::mapMatch)
                .optional();
    }

    void insert(UUID id, UUID tenantId, UUID teamId, String competition, String opponent, String location,
                UUID courtId, Instant matchDate, boolean isHome, String status, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO match (id, tenant_id, team_id, competition, opponent, location, court_id,
                                           match_date, is_home, status, created_by)
                        VALUES (:id, :tenant, :team, :comp, :opp, :loc, :court, :date, :home, :status, :actor)
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("team", teamId)
                .param("comp", competition)
                .param("opp", opponent)
                .param("loc", location)
                .param("court", courtId)
                .param("date", matchDate == null ? null : java.time.OffsetDateTime.ofInstant(matchDate, java.time.ZoneOffset.UTC))
                .param("home", isHome)
                .param("status", status)
                .param("actor", actorId)
                .update();
    }

    boolean update(UUID tenantId, UUID id, UUID teamId, String competition, String opponent, String location,
                   UUID courtId, Instant matchDate, boolean isHome, String status) {
        return jdbc.sql("""
                        UPDATE match
                        SET team_id = :team, competition = :comp, opponent = :opp, location = :loc,
                            court_id = :court, match_date = :date, is_home = :home, status = :status,
                            updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .param("team", teamId)
                .param("comp", competition)
                .param("opp", opponent)
                .param("loc", location)
                .param("court", courtId)
                .param("date", matchDate == null ? null : java.time.OffsetDateTime.ofInstant(matchDate, java.time.ZoneOffset.UTC))
                .param("home", isHome)
                .param("status", status)
                .update() == 1;
    }

    boolean anonymize(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        UPDATE match
                        SET anonymized_at = now(), status = 'CANCELLED', updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL
                        """)
                .param("tenant", tenantId)
                .param("id", id)
                .update() == 1;
    }

    // --- set scores ---

    List<SetScoreRow> listSetScores(UUID tenantId, UUID matchId) {
        return jdbc.sql("""
                        SELECT set_number, our_points, opponent_points
                        FROM set_score
                        WHERE tenant_id = :tenant AND match_id = :match
                        ORDER BY set_number
                        """)
                .param("tenant", tenantId)
                .param("match", matchId)
                .query((rs, i) -> new SetScoreRow(
                        rs.getInt("set_number"),
                        rs.getInt("our_points"),
                        rs.getInt("opponent_points")))
                .list();
    }

    void upsertSetScore(UUID tenantId, UUID matchId, int setNumber, int ourPoints, int opponentPoints, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO set_score (tenant_id, match_id, set_number, our_points, opponent_points, created_by)
                        VALUES (:tenant, :match, :set, :our, :opp, :actor)
                        ON CONFLICT (tenant_id, match_id, set_number)
                        DO UPDATE SET our_points = EXCLUDED.our_points, opponent_points = EXCLUDED.opponent_points,
                                      updated_at = now()
                        """)
                .param("tenant", tenantId)
                .param("match", matchId)
                .param("set", setNumber)
                .param("our", ourPoints)
                .param("opp", opponentPoints)
                .param("actor", actorId)
                .update();
    }

    boolean deleteSetScore(UUID tenantId, UUID matchId, int setNumber) {
        return jdbc.sql("""
                        DELETE FROM set_score
                        WHERE tenant_id = :tenant AND match_id = :match AND set_number = :set
                        """)
                .param("tenant", tenantId)
                .param("match", matchId)
                .param("set", setNumber)
                .update() == 1;
    }

    // --- call ups ---

    List<CallUpRow> listCallUps(UUID tenantId, UUID matchId) {
        return jdbc.sql("""
                        SELECT c.athlete_id, a.full_name, c.status, c.response, c.responded_at
                        FROM call_up c
                        JOIN athlete a ON a.tenant_id = c.tenant_id AND a.id = c.athlete_id
                        WHERE c.tenant_id = :tenant AND c.match_id = :match AND a.anonymized_at IS NULL
                        ORDER BY a.full_name, a.id
                        """)
                .param("tenant", tenantId)
                .param("match", matchId)
                .query((rs, i) -> new CallUpRow(
                        rs.getObject("athlete_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("status"),
                        rs.getString("response"),
                        rs.getObject("responded_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("responded_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    boolean callUpExists(UUID tenantId, UUID matchId, UUID athleteId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM call_up
                            WHERE tenant_id = :tenant AND match_id = :match AND athlete_id = :athlete)
                        """)
                .param("tenant", tenantId)
                .param("match", matchId)
                .param("athlete", athleteId)
                .query(Boolean.class)
                .single();
    }

    void insertCallUp(UUID tenantId, UUID matchId, UUID athleteId, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO call_up (tenant_id, match_id, athlete_id, status, created_by)
                        VALUES (:tenant, :match, :athlete, 'CALLED_UP', :actor)
                        """)
                .param("tenant", tenantId)
                .param("match", matchId)
                .param("athlete", athleteId)
                .param("actor", actorId)
                .update();
    }

    void updateCallUpResponse(UUID tenantId, UUID matchId, UUID athleteId, String response, Instant respondedAt) {
        jdbc.sql("""
                        UPDATE call_up
                        SET response = :response, responded_at = :responded, status = :status, updated_at = now()
                        WHERE tenant_id = :tenant AND match_id = :match AND athlete_id = :athlete
                        """)
                .param("tenant", tenantId)
                .param("match", matchId)
                .param("athlete", athleteId)
                .param("response", response)
                .param("responded", respondedAt == null ? null
                        : OffsetDateTime.ofInstant(respondedAt, java.time.ZoneOffset.UTC))
                .param("status", response == null ? "CALLED_UP" : response)
                .update();
    }

    boolean deleteCallUp(UUID tenantId, UUID matchId, UUID athleteId) {
        return jdbc.sql("""
                        DELETE FROM call_up
                        WHERE tenant_id = :tenant AND match_id = :match AND athlete_id = :athlete
                        """)
                .param("tenant", tenantId)
                .param("match", matchId)
                .param("athlete", athleteId)
                .update() == 1;
    }

    // --- validations ---

    boolean venueExists(UUID tenantId, UUID venueId) {
        if (venueId == null) return true;
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM venue WHERE tenant_id = :tenant AND id = :id AND anonymized_at IS NULL)
                        """)
                .param("tenant", tenantId)
                .param("id", venueId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    void audit(UUID tenantId, UUID actorId, String action, UUID entityId) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, user_id, action, entity, entity_id)
                        VALUES (:tenant, :actor, :action, 'match', :entity)
                        """)
                .param("tenant", tenantId)
                .param("actor", actorId)
                .param("action", action)
                .param("entity", entityId)
                .update();
    }

    private static MatchRow mapMatch(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MatchRow(
                rs.getObject("id", UUID.class),
                rs.getObject("team_id", UUID.class),
                rs.getString("competition"),
                rs.getString("opponent"),
                rs.getString("location"),
                rs.getObject("court_id", UUID.class),
                rs.getObject("match_date", OffsetDateTime.class).toInstant(),
                rs.getBoolean("is_home"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}