import { useEffect, useState } from "react";
import {
  Activity,
  BookOpen,
  CalendarDays,
  ChevronRight,
  CircleUserRound,
  CreditCard,
  FileText,
  LayoutDashboard,
  LogOut,
  Menu,
  Sparkles,
  Swords,
  Users,
  X
} from "lucide-react";
import { api, type Role } from "../api";
import type { View } from "../types";
import { Athletes } from "../features/athletes/Athletes";
import { Billing } from "../features/billing/Billing";
import { Matches } from "../features/matches/Matches";
import { Overview } from "../features/overview/Overview";
import { Seasons } from "../features/seasons/Seasons";
import { Teams } from "../features/teams/Teams";
import { Training } from "../features/training/Training";

const TITLES: Record<View, string> = {
  overview: "Visão geral",
  athletes: "Atletas",
  teams: "Equipas",
  seasons: "Épocas e escalões",
  training: "Treinos",
  matches: "Jogos e convocatórias",
  billing: "Quotas e financeiro"
};

/** Estrutura da aplicação autenticada: menu lateral, barra superior e ecrã ativo. */
export function Shell({ onLogout }: { onLogout: () => void }) {
  const [view, setView] = useState<View>("overview");
  const [roles, setRoles] = useState<Role[]>([]);
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => {
    api
      .me()
      .then(me => setRoles(me.roles))
      .catch(onLogout);
  }, [onLogout]);

  const canManage = roles.some(role => role === "ADMIN" || role === "DIRECAO");
  const canManageBilling = roles.some(role => role === "ADMIN" || role === "DIRECAO" || role === "FINANCEIRO");

  const navigate = (target: View) => {
    setView(target);
    setMobileOpen(false);
  };

  return (
    <div className="app-shell">
      <aside className={`sidebar ${mobileOpen ? "is-open" : ""}`}>
        <div className="sidebar-head">
          <div className="brand brand-light">
            <span className="brand-mark">V</span>
            <span>
              Clube<span className="brand-dot">.</span>
            </span>
          </div>
          <button className="icon-button mobile-only" onClick={() => setMobileOpen(false)}>
            <X size={20} />
          </button>
        </div>
        <div className="club-switcher">
          <div className="club-avatar">SC</div>
          <div>
            <strong>Sport Club</strong>
            <small>Clube principal</small>
          </div>
          <ChevronRight size={16} />
        </div>
        <nav className="nav">
          <p className="nav-label">PRINCIPAL</p>
          <NavItem
            active={view === "overview"}
            onClick={() => navigate("overview")}
            icon={<LayoutDashboard size={18} />}
            label="Visão geral"
          />
          <NavItem
            active={view === "athletes"}
            onClick={() => navigate("athletes")}
            icon={<Users size={18} />}
            label="Atletas"
          />
          <NavItem
            active={view === "teams"}
            onClick={() => navigate("teams")}
            icon={<Activity size={18} />}
            label="Equipas"
          />
          <NavItem
            active={view === "seasons"}
            onClick={() => navigate("seasons")}
            icon={<BookOpen size={18} />}
            label="Épocas e escalões"
          />
          <NavItem
            active={view === "training"}
            onClick={() => navigate("training")}
            icon={<CalendarDays size={18} />}
            label="Treinos"
          />
          <NavItem
            active={view === "matches"}
            onClick={() => navigate("matches")}
            icon={<Swords size={18} />}
            label="Jogos e convocatórias"
          />
          <p className="nav-label nav-space">CLUBE</p>
          <NavItem
            active={view === "billing"}
            onClick={() => navigate("billing")}
            icon={<CreditCard size={18} />}
            label="Quotas e financeiro"
          />
          <NavItem disabled icon={<FileText size={18} />} label="Documentos" />
          <NavItem disabled icon={<Sparkles size={18} />} label="Definições" />
        </nav>
        <div className="sidebar-bottom">
          <div className="user-mini">
            <div className="user-avatar">IR</div>
            <div>
              <strong>Administrador</strong>
              <small>{roles.join(" · ") || "Membro"}</small>
            </div>
          </div>
          <button className="logout-button" onClick={onLogout} aria-label="Terminar sessão">
            <LogOut size={18} />
          </button>
        </div>
      </aside>
      {mobileOpen && (
        <button className="sidebar-overlay mobile-only" onClick={() => setMobileOpen(false)} aria-label="Fechar menu" />
      )}
      <main className="main-content">
        <header className="topbar">
          <button className="icon-button mobile-only" onClick={() => setMobileOpen(true)}>
            <Menu size={21} />
          </button>
          <div className="breadcrumb">
            <span>Sport Club</span>
            <ChevronRight size={15} />
            <strong>{TITLES[view]}</strong>
          </div>
          <div className="topbar-actions">
            <span className="status-dot">Sistema operacional</span>
            <CircleUserRound size={21} />
          </div>
        </header>
        <div className="content-wrap">
          {view === "overview" && <Overview onNavigate={setView} canManage={canManage} />}
          {view === "athletes" && <Athletes />}
          {view === "teams" && <Teams canManage={canManage} />}
          {view === "seasons" && <Seasons canManage={canManage} />}
          {view === "training" && <Training canManage={canManage} />}
          {view === "matches" && <Matches canManage={canManage} />}
          {view === "billing" && <Billing canManage={canManageBilling} />}
        </div>
      </main>
    </div>
  );
}

function NavItem({
  icon,
  label,
  active,
  disabled,
  onClick
}: {
  icon: React.ReactNode;
  label: string;
  active?: boolean;
  disabled?: boolean;
  onClick?: () => void;
}) {
  return (
    <button className={`nav-item ${active ? "active" : ""}`} disabled={disabled} onClick={onClick}>
      {icon}
      <span>{label}</span>
      {active && <span className="nav-active-mark" />}
    </button>
  );
}
