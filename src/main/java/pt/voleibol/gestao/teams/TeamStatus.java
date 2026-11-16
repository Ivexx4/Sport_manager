package pt.voleibol.gestao.teams;

/** Estado de uma equipa ({@code team.status}). */
public enum TeamStatus {

    ACTIVE, INACTIVE;

    public static final TeamStatus DEFAULT = ACTIVE;
}
