package pt.voleibol.gestao.teams;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.Db;
import pt.voleibol.gestao.shared.Rules;

/**
 * Visibilidade de uma equipa para o ator, partilhada pelos serviços que dependem de equipas (jogos e
 * treinos), para que as regras não divirjam entre domínios.
 */
@Component
public class TeamAccess {

    private final JdbcClient jdbc;

    public TeamAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Equipa do clube, não anonimizada. */
    boolean exists(UUID tenantId, UUID teamId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM team
                        WHERE tenant_id = :t AND id = :id AND anonymized_at IS NULL)
                        """)
                .param("t", tenantId).param("id", teamId));
    }

    /** O ator é staff da equipa. */
    boolean visibleToStaff(UUID tenantId, UUID teamId, UUID actorId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM team t
                            WHERE t.tenant_id = :t AND t.id = :id AND t.anonymized_at IS NULL
                              AND EXISTS (SELECT 1 FROM team_staff s
                                          WHERE s.tenant_id = t.tenant_id AND s.team_id = t.id
                                            AND s.user_id = :a))
                        """)
                .param("t", tenantId).param("id", teamId).param("a", actorId));
    }

    /** O ator é o atleta da equipa ou o encarregado de um atleta da equipa. */
    boolean hasAthleteLinked(UUID tenantId, UUID teamId, UUID actorId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM team_athlete ta
                            JOIN athlete a ON a.tenant_id = ta.tenant_id AND a.id = ta.athlete_id
                            WHERE ta.tenant_id = :t AND ta.team_id = :team AND a.anonymized_at IS NULL
                              AND (a.user_id = :a OR EXISTS (
                                  SELECT 1 FROM athlete_guardian g
                                  WHERE g.tenant_id = a.tenant_id AND g.athlete_id = a.id AND g.user_id = :a)))
                        """)
                .param("t", tenantId).param("team", teamId).param("a", actorId));
    }

    /** O atleta pertence ao plantel da equipa. */
    public boolean athleteOnTeam(UUID tenantId, UUID teamId, UUID athleteId) {
        return Db.exists(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM team_athlete
                        WHERE tenant_id = :t AND team_id = :team AND athlete_id = :a)
                        """)
                .param("t", tenantId).param("team", teamId).param("a", athleteId));
    }

    /** Gestores veem todas; o treinador as suas; atleta/encarregado as que têm o atleta ligado. */
    public boolean visible(UUID tenantId, UUID teamId, UUID actorId, Set<String> roles) {
        if (Rules.isManager(roles)) {
            return exists(tenantId, teamId);
        }
        if (roles.contains("TREINADOR")) {
            return visibleToStaff(tenantId, teamId, actorId);
        }
        if (roles.contains("ATLETA") || roles.contains("ENCARREGADO")) {
            return hasAthleteLinked(tenantId, teamId, actorId);
        }
        return false;
    }

    /**
     * Falha com 403 quando o papel não tem acesso à gestão desportiva e com 404 quando a equipa existe
     * mas está fora do alcance do ator.
     */
    public void requireVisible(UUID tenantId, UUID teamId, UUID actorId, Set<String> roles) {
        if (visible(tenantId, teamId, actorId, roles)) {
            return;
        }
        if (Rules.isManager(roles) || roles.contains("TREINADOR")
                || roles.contains("ATLETA") || roles.contains("ENCARREGADO")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipa não encontrada");
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
