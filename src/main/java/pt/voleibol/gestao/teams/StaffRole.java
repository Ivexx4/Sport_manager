package pt.voleibol.gestao.teams;

/** Papel de um membro do staff técnico de uma equipa ({@code team_staff.role}). */
public enum StaffRole {

    TREINADOR, TREINADOR_ADJUNTO;

    public static final StaffRole DEFAULT = TREINADOR;
}
