package pt.voleibol.gestao.teams;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Catálogo federal FPV, comum a todos os clubes e épocas. */
@Repository
class AgeGroupRepository {

    record AgeGroupRow(UUID id, String name, int minAge, Integer maxAge, boolean isActive,
                       Instant createdAt, Instant updatedAt) {
        AgeGroupRow(UUID id, UUID ignoredSeasonId, String name, int minBirthYear, int maxBirthYear,
                    boolean isActive, Instant createdAt, Instant updatedAt) {
            this(id, name, 0, null, isActive, createdAt, updatedAt);
        }

        UUID seasonId() {
            return null;
        }
    }

    /** Compatibilidade binária para testes/integrações antigas; o catálogo já não aceita escritas. */
    record Data(UUID seasonId, String name, int minBirthYear, int maxBirthYear, boolean isActive) {
        Data(UUID seasonId, String name, int minBirthYear, int maxBirthYear) {
            this(seasonId, name, minBirthYear, maxBirthYear, true);
        }

    }

    void insert(UUID id, UUID tenantId, Data data, UUID actorId) {
        throw new UnsupportedOperationException("O catálogo federal não aceita escalões personalizados");
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

    List<AgeGroupRow> listBySeason(UUID ignoredTenantId, UUID ignoredSeasonId) {
        return list(ignoredTenantId);
    }

    Optional<AgeGroupRow> findById(UUID ignoredTenantId, UUID ageGroupId) {
        return jdbc.sql(SELECT + " WHERE g.id = :id")
                .param("id", ageGroupId)
                .query(AgeGroupRepository::map).optional();
    }

    private static AgeGroupRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Integer maxAge = (Integer) rs.getObject("max_age");
        return new AgeGroupRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getInt("min_age"),
                maxAge,
                rs.getBoolean("is_active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
