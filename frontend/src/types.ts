/** Ecrãs do painel: o menu lateral e o conteúdo principal partilham esta união. */
export type View =
  "overview" | "athletes" | "teams" | "seasons" | "training" | "matches" | "billing" | "documents" | "settings";

export type Toast = { kind: "success" | "error"; message: string };
