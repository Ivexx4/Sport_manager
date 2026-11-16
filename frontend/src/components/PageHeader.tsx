import type { ReactNode } from "react";

/** Cabeçalho dos ecrãs: etiqueta, título, descrição e ações à direita. */
export function PageHeader({
  eyebrow,
  title,
  description,
  action
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p className="muted">{description}</p>
      </div>
      <div className="heading-actions">{action}</div>
    </div>
  );
}
