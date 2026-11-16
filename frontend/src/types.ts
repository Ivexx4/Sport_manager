/** Ecrãs do painel: o menu lateral e o conteúdo principal partilham esta união. */
export type View = "overview" | "athletes" | "teams" | "seasons" | "training" | "matches" | "billing";

export type Toast = { kind: "success" | "error"; message: string };

/** Callback que os ecrãs usam para mostrar uma notificação no canto inferior. */
export type ShowToast = (toast: Toast) => void;

/** Callback de erro usado pelos formulários dos modais. */
export type ReportError = (message: string) => void;
