package pt.voleibol.gestao.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regra federativa FPV: dentro da faixa é elegível; mais novo do que o escalão é uma subida e exige
 * autorização para a época; mais velho é uma descida e é sempre proibida.
 */
class AgeGroupEligibilityTest {

    private static final AgeGroupRepository.AgeGroupRow CADETES =
            grupo("Cadetes", 15, 15);
    private static final AgeGroupRepository.AgeGroupRow SUB21 =
            grupo("Sub21", 18, 20);
    private static final AgeGroupRepository.AgeGroupRow SENIORES =
            grupo("Seniores", 19, null);
    private static final AgeGroupRepository.AgeGroupRow VETERANOS_B =
            grupo("Veteranos B", 45, null);

    @Test
    void idadeDentroDaFaixaEElegivel() {
        assertThat(AgeGroupEligibility.evaluate(15, CADETES)).isEqualTo(AgeGroupEligibility.Verdict.ELIGIBLE);
        assertThat(AgeGroupEligibility.eligible(19, SUB21)).isTrue();
        assertThatCode(() -> AgeGroupEligibility.require(15, CADETES, false, "teste")).doesNotThrowAnyException();
    }

    @Test
    void atletaMaisNovoExigeAutorizacao() {
        assertThat(AgeGroupEligibility.evaluate(14, CADETES)).isEqualTo(AgeGroupEligibility.Verdict.PROMOTION_REQUIRED);
        assertThat(AgeGroupEligibility.eligible(14, CADETES)).isFalse();
        assertThatThrownBy(() -> AgeGroupEligibility.require(14, CADETES, false, "plantel da equipa"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Subida de escalão exige autorização")
                .hasMessageContaining("plantel da equipa");
        assertThatCode(() -> AgeGroupEligibility.require(14, CADETES, true, "plantel da equipa"))
                .doesNotThrowAnyException();
    }

    @Test
    void atletaMaisVelhoEDescidaSempreProibida() {
        assertThat(AgeGroupEligibility.evaluate(16, CADETES)).isEqualTo(AgeGroupEligibility.Verdict.DESCENT_FORBIDDEN);
        assertThatThrownBy(() -> AgeGroupEligibility.require(16, CADETES, false, "inscrição na época"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Proibido atuar em escalão inferior");
        // Nem com autorização: a promoção só desbloqueia a subida.
        assertThatThrownBy(() -> AgeGroupEligibility.require(16, CADETES, true, "inscrição na época"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Proibido atuar em escalão inferior");
    }

    @Test
    void senioresAceitamDezanoveEMaisESoSubidaExigeAutorizacao() {
        assertThat(AgeGroupEligibility.evaluate(19, SENIORES)).isEqualTo(AgeGroupEligibility.Verdict.ELIGIBLE);
        assertThat(AgeGroupEligibility.evaluate(34, SENIORES)).isEqualTo(AgeGroupEligibility.Verdict.ELIGIBLE);
        assertThat(AgeGroupEligibility.evaluate(18, SENIORES)).isEqualTo(AgeGroupEligibility.Verdict.PROMOTION_REQUIRED);
        assertThatThrownBy(() -> AgeGroupEligibility.require(18, SENIORES, false, "plantel da equipa"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Subida de escalão exige autorização");
        assertThatCode(() -> AgeGroupEligibility.require(18, SENIORES, true, "plantel da equipa"))
                .doesNotThrowAnyException();
    }

    @Test
    void veteranosNaoTemLimiteSuperiorMasNaoAceitamMaisNovos() {
        assertThat(AgeGroupEligibility.evaluate(45, VETERANOS_B)).isEqualTo(AgeGroupEligibility.Verdict.ELIGIBLE);
        assertThat(AgeGroupEligibility.evaluate(70, VETERANOS_B)).isEqualTo(AgeGroupEligibility.Verdict.ELIGIBLE);
        assertThat(AgeGroupEligibility.evaluate(44, VETERANOS_B)).isEqualTo(AgeGroupEligibility.Verdict.PROMOTION_REQUIRED);
    }

    @Test
    void erroDeSubidaEDescidaSaoBadRequest() {
        assertThatThrownBy(() -> AgeGroupEligibility.require(14, CADETES, false, "teste"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> AgeGroupEligibility.require(16, CADETES, false, "teste"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void idadeECalculadaNaDataDeReferenciaDaEpoca() {
        LocalDate nascimento = LocalDate.of(2011, 3, 1);

        // A data de referência manda sobre o início da época.
        assertThat(AgeGroupEligibility.ageAt(nascimento, epoca(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1))))
                .isEqualTo(14);
        assertThat(AgeGroupEligibility.ageAt(nascimento, epoca(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1))))
                .isEqualTo(15);
    }

    @Test
    void idadeUsaOInicioDaEpocaQuandoNaoHaDataDeReferencia() {
        LocalDate nascimento = LocalDate.of(2011, 3, 1);

        assertThat(AgeGroupEligibility.ageAt(nascimento, epoca(null, LocalDate.of(2026, 6, 1)))).isEqualTo(15);
        // Sem data de referência nem início da época cai na data corrente, sem falhar.
        assertThat(AgeGroupEligibility.ageAt(nascimento, epoca(null, null))).isNotNegative();
    }

    private static AgeGroupRepository.AgeGroupRow grupo(String nome, int idadeMinima, Integer idadeMaxima) {
        return new AgeGroupRepository.AgeGroupRow(UUID.randomUUID(), nome, idadeMinima, idadeMaxima, true,
                Instant.EPOCH, Instant.EPOCH);
    }

    private static SeasonRepository.SeasonRow epoca(LocalDate cutoffDate, LocalDate startDate) {
        return new SeasonRepository.SeasonRow(UUID.randomUUID(), "2025/26", startDate,
                LocalDate.of(2026, 6, 30), cutoffDate, true, Instant.EPOCH, Instant.EPOCH);
    }
}
