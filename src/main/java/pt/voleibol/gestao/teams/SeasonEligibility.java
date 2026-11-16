package pt.voleibol.gestao.teams;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import pt.voleibol.gestao.shared.Rules;

/**
 * Reavalia a regra federativa sobre o estado já persistido, para que alterações a dados de que a
 * elegibilidade depende (data de nascimento, data de referência da época, escalão da equipa) não
 * deixem o clube com inscrições ou plantéis ilegais.
 *
 * <p>É chamado depois da escrita e dentro da mesma transação, pelo que a exceção faz rollback. Não
 * há override: para aplicar uma alteração bloqueada é preciso revogar ou corrigir primeiro o que a
 * viola. Partilhado com o pacote de atletas, seguindo o padrão de {@link TeamAccess}.
 */
@Service
public class SeasonEligibility {

    private final TeamRepository teams;
    private final PromotionRepository promotions;

    SeasonEligibility(TeamRepository teams, PromotionRepository promotions) {
        this.teams = teams;
        this.promotions = promotions;
    }

    /** Todas as inscrições e plantéis do atleta, em qualquer época. */
    public void requireAthleteStillEligible(UUID tenantId, UUID athleteId) {
        require(tenantId, teams.registrationsOfAthlete(tenantId, athleteId));
        require(tenantId, teams.rosterOfAthlete(tenantId, athleteId));
    }

    /** Inscrições e plantéis de todas as equipas da época. */
    public void requireSeasonStillValid(UUID tenantId, UUID seasonId) {
        require(tenantId, teams.registrationsOfSeason(tenantId, seasonId));
        require(tenantId, teams.rosterOfSeason(tenantId, seasonId));
    }

    /** Plantel de uma equipa. */
    public void requireTeamStillValid(UUID tenantId, UUID teamId) {
        require(tenantId, teams.rosterOfTeam(tenantId, teamId));
    }

    private void require(UUID tenantId, List<TeamRepository.EligibilityRow> rows) {
        for (TeamRepository.EligibilityRow row : rows) {
            int age = AgeGroupEligibility.ageAt(row.birthDate(), row.season());
            AgeGroupEligibility.Verdict verdict = AgeGroupEligibility.evaluate(age, row.ageGroup());
            if (verdict == AgeGroupEligibility.Verdict.ELIGIBLE) continue;
            if (verdict == AgeGroupEligibility.Verdict.PROMOTION_REQUIRED && promotions.activeExists(
                    tenantId, row.season().id(), row.ageGroup().id(), row.athleteId())) {
                continue;
            }
            throw Rules.badRequest("A alteração tornaria o atleta inelegível em " + row.ageGroup().name()
                    + " (" + row.context() + ")");
        }
    }
}
