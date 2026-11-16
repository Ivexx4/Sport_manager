package pt.voleibol.gestao.training;

/** Estado de uma sessão de treino ({@code training_session.status}). */
public enum TrainingStatus {

    ACTIVE, CANCELLED, COMPLETED;

    public static final TrainingStatus DEFAULT = ACTIVE;
}
