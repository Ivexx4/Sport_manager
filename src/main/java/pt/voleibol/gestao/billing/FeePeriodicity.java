package pt.voleibol.gestao.billing;

/** Periodicidade de um plano de quotas ({@code fee_plan.periodicity}). */
public enum FeePeriodicity {

    ONE_TIME, MONTHLY, QUARTERLY, YEARLY;

    public static final FeePeriodicity DEFAULT = MONTHLY;

    public boolean recurring() {
        return this != ONE_TIME;
    }
}
