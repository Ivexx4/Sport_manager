package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import pt.voleibol.gestao.shared.Db;

/** Catálogo federal FPV, comum a todos os clubes e épocas. */
@Repository
class AgeGroupRepository {

    record AgeGroupRow(UUID id, String name, int minAge, Integer maxAge, boolean isActive,
                       Instant createdAt, Instant updatedAt) {
    }

    private static final String SELECT = """
            SELECT g.id, g.name, g.min_age, g.max_age, g.is_active, g.created_at, g.updated_at
            FROM age_group g
            """;

    private final JdbcClient jdbc;

    AgeGroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<AgeGroupRow> list(UUID ignoredTenantId) {
        return jdbc.sql(SELECT + " WHERE g.is_active ORDER BY g.min_age, g.max_age NULLS LAST, g.name")
                .query(AgeGroupRepository::map).list();
    }

    Optional<AgeGroupRow> findById(UUID ignoredTenantId, UUID ageGroupId) {
        return jdbc.sql(SELECT + " WHERE g.id = :id")
                .param("id", ageGroupId)
                .query(AgeGroupRepository::map).optional();
    }

    private static AgeGroupRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Integer maxAge = (Integer) rs.getObject("max_age");
        return new AgeGroupRow(Db.uuid(rs, "id"), rs.getString("name"), rs.getInt("min_age"), maxAge,
                rs.getBoolean("is_active"), Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));
    }
}
