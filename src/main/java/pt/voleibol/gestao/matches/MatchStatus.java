package pt.voleibol.gestao.matches;

/** Estado de um jogo ({@code match.status}). */
public enum MatchStatus {

    SCHEDULED, PLAYED, CANCELLED, POSTPONED;

    public static final MatchStatus DEFAULT = SCHEDULED;
}
