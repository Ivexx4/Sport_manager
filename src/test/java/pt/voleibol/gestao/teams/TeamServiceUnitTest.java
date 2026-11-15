package pt.voleibol.gestao.teams;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TeamServiceUnitTest {

    @Test
    void permiteVeteranosEmSenioresComIdadeCalculadaNoAnoDoCampeonato() {
        SeasonRepository.SeasonRow season = new SeasonRepository.SeasonRow(
                UUID.randomUUID(), "2025/26", LocalDate.of(2025, 9, 1),
                LocalDate.of(2026, 6, 30), null, true, Instant.EPOCH, Instant.EPOCH);
        AgeGroupRepository.AgeGroupRow seniors = new AgeGroupRepository.AgeGroupRow(
                UUID.randomUUID(), season.id(), "Seniores", 1900, 2007, true,
                Instant.EPOCH, Instant.EPOCH);
        AgeGroupRepository.AgeGroupRow juniors = new AgeGroupRepository.AgeGroupRow(
                UUID.randomUUID(), season.id(), "Juniores", 2008, 2009, true,
                Instant.EPOCH, Instant.EPOCH);

        assertThat(TeamService.isEligibleForSeniors(season, seniors, 2007)).isTrue();
        assertThat(TeamService.isEligibleForSeniors(season, seniors, 2008)).isFalse();
        assertThat(TeamService.isEligibleForSeniors(season, juniors, 1990)).isFalse();
    }
}
