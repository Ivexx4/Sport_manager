package pt.voleibol.gestao.teams;

import java.time.LocalDate;
import java.time.Period;

import pt.voleibol.gestao.shared.Rules;

/**
 * Regra federativa FPV de elegibilidade por escalão, partilhada pelo plantel, pela inscrição na
 * época e pelas revalidações. A idade é sempre calculada na data de referência da época.
 *
 * <p>Um atleta mais novo do que o escalão está a subir de escalão e depende de autorização
 * excepcional para a época; um atleta mais velho do que o escalão está a jogar num escalão
 * inferior, o que o regulamento proíbe.
 */
final class AgeGroupEligibility {

    /** Comparação entre a idade do atleta e os limites do escalão. */
    enum Verdict {
        /** Idade dentro dos limites do escalão. */
        ELIGIBLE,
        /** Atleta mais novo do que o escalão: subida, sujeita a autorização. */
        PROMOTION_REQUIRED,
        /** Atleta mais velho do que o escalão: escalão inferior, proibido. */
        DESCENT_FORBIDDEN
    }

    private AgeGroupEligibility() {
    }

    static Verdict evaluate(int age, AgeGroupRepository.AgeGroupRow group) {
        if (age < group.minAge()) return Verdict.PROMOTION_REQUIRED;
        if (group.maxAge() != null && age > group.maxAge()) return Verdict.DESCENT_FORBIDDEN;
        return Verdict.ELIGIBLE;
    }

    static boolean eligible(int age, AgeGroupRepository.AgeGroupRow group) {
        return evaluate(age, group) == Verdict.ELIGIBLE;
    }

    /**
     * Aplica a regra: a subida sem autorização e a descida são recusadas com {@code 400}.
     * {@code context} identifica a origem da validação na mensagem de erro.
     */
    static void require(int age, AgeGroupRepository.AgeGroupRow group, boolean authorized, String context) {
        Verdict verdict = evaluate(age, group);
        if (verdict == Verdict.ELIGIBLE) return;
        if (verdict == Verdict.DESCENT_FORBIDDEN)
            throw Rules.badRequest("Proibido atuar em escalão inferior conforme regulamento FPV (" + context + ")");
        if (!authorized)
            throw Rules.badRequest("Subida de escalão exige autorização excepcional para a época (" + context + ")");
    }

    /**
     * Idade exata na data de referência da época. Sem data de corte usa o início da época, para
     * que a decisão não mude com o passar dos dias.
     */
    static int ageAt(LocalDate birthDate, SeasonRepository.SeasonRow season) {
        LocalDate reference = season.cutoffDate() != null ? season.cutoffDate()
                : season.startDate() != null ? season.startDate() : LocalDate.now();
        return Period.between(birthDate, reference).getYears();
    }
}
