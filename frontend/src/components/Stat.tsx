import type { ReactNode } from "react";
import { ChevronRight } from "lucide-react";

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
