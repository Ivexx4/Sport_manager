package pt.voleibol.gestao.billing;

/** Estado de uma cobrança ({@code charge.status}). */
public enum ChargeStatus {

    PENDING, PAID, OVERDUE, CANCELLED;

    public static final ChargeStatus DEFAULT = PENDING;
}
