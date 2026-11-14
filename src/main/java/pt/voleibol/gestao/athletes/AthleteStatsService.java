package pt.voleibol.gestao.athletes;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

@Service
class AthleteStatsService {

    record Stats(long matchesCalledUp, long matchesPlayed, long callUpsConfirmed, long callUpsDeclined,
                 long trainingsRegistered, long trainingsPresent) {
    }

    private final JdbcClient jdbc;
    private final AthleteRepository athletes;

    AthleteStatsService(JdbcClient jdbc, AthleteRepository athletes) {
        this.jdbc = jdbc;
        this.athletes = athletes;
    }

    @Transactional(readOnly = true)
    Stats get(UUID actorId, Set<String> roles, UUID athleteId) {
        UUID t = TenantContext.current().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube"));
        if (athletes.findVisible(t, athleteId, actorId, roles).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Atleta não encontrado");
        }
        long calledUp = jdbc.sql("""
                SELECT count(*) FROM call_up c JOIN match m ON m.tenant_id=c.tenant_id AND m.id=c.match_id
                WHERE c.tenant_id=:t AND c.athlete_id=:a
                """).param("t", t).param("a", athleteId).query(Long.class).single();
        long played = jdbc.sql("""
                SELECT count(*) FROM call_up c JOIN match m ON m.tenant_id=c.tenant_id AND m.id=c.match_id
                WHERE c.tenant_id=:t AND c.athlete_id=:a AND m.status='PLAYED'
                """).param("t", t).param("a", athleteId).query(Long.class).single();
        long confirmed = jdbc.sql("""
                SELECT count(*) FROM call_up WHERE tenant_id=:t AND athlete_id=:a AND response='CONFIRMED'
                """).param("t", t).param("a", athleteId).query(Long.class).single();
        long declined = jdbc.sql("""
                SELECT count(*) FROM call_up WHERE tenant_id=:t AND athlete_id=:a AND response='DECLINED'
                """).param("t", t).param("a", athleteId).query(Long.class).single();
        long trainings = jdbc.sql("""
                SELECT count(*) FROM attendance WHERE tenant_id=:t AND athlete_id=:a
                """).param("t", t).param("a", athleteId).query(Long.class).single();
        long present = jdbc.sql("""
                SELECT count(*) FROM attendance WHERE tenant_id=:t AND athlete_id=:a AND status IN ('PRESENT','LATE')
                """).param("t", t).param("a", athleteId).query(Long.class).single();
        return new Stats(calledUp, played, confirmed, declined, trainings, present);
    }
}