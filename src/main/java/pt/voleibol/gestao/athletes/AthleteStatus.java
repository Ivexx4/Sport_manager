package pt.voleibol.gestao.athletes;

/** Estado de um atleta ({@code athlete.status}). */
public enum AthleteStatus {

    ACTIVE, INACTIVE;

    /** Valor usado quando o pedido não indica estado. */
    public static final AthleteStatus DEFAULT = ACTIVE;
}
