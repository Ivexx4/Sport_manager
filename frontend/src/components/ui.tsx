import type { ReactNode } from "react";
import { ChevronRight, X } from "lucide-react";

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

export function Loading() {
  return (
    <div className="empty-state">
      <div className="loader" />
      <p>A carregar...</p>
    </div>
  );
}

export function Empty({ icon, title, text }: { icon: ReactNode; title: string; text: string }) {
  return (
    <div className="empty-state">
      <div className="empty-icon">{icon}</div>
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}

export function Modal({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  return (
    <div className="modal-backdrop" onMouseDown={event => event.target === event.currentTarget && onClose()}>
      <div className="modal">
        <div className="modal-head">
          <div>
            <p className="eyebrow">CLUBE</p>
            <h2>{title}</h2>
          </div>
          <button className="icon-button" onClick={onClose}>
            <X size={20} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

/** Cartão de indicador da visão geral e do financeiro. */
export function Stat({
  icon,
  label,
  value,
  color,
  text,
  onClick
}: {
  icon: ReactNode;
  label: string;
  value: number | string;
  color: string;
  text?: boolean;
  onClick?: () => void;
}) {
  return (
    <button className={`stat-card ${color}`} onClick={onClick}>
      <span className="stat-icon">{icon}</span>
      <span className="stat-label">{label}</span>
      <strong className={text ? "stat-text" : ""}>{value}</strong>
      <ChevronRight className="stat-arrow" size={17} />
    </button>
  );
}

/** Rodapé padrão dos formulários: cancelar à esquerda, guardar à direita. */
export function ModalActions({
  saving,
  disabled,
  onClose,
  submitLabel = "Guardar"
}: {
  saving: boolean;
  disabled?: boolean;
  onClose: () => void;
  submitLabel?: string;
}) {
  return (
    <div className="modal-actions">
      <button type="button" className="button button-secondary" onClick={onClose}>
        Cancelar
      </button>
      <button className="button button-primary" disabled={saving || disabled}>
        {saving ? "A guardar..." : submitLabel}
      </button>
    </div>
  );
}
