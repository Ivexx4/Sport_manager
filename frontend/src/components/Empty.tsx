import type { ReactNode } from "react";

/** Estado vazio de uma lista ou tabela: ícone, título e uma frase de orientação. */
export function Empty({ icon, title, text }: { icon: ReactNode; title: string; text: string }) {
  return (
    <div className="empty-state">
      <div className="empty-icon">{icon}</div>
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}
