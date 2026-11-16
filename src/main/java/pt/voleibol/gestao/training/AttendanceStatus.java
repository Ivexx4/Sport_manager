package pt.voleibol.gestao.training;

/** Estado de presença de um atleta numa sessão de treino ({@code attendance.status}). */
public enum AttendanceStatus {

    PRESENT, ABSENT, JUSTIFIED, LATE;

    public static final AttendanceStatus DEFAULT = PRESENT;
}
