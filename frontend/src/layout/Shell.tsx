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
import type { Toast, View } from "../types";
import { Athletes } from "../views/Athletes";
import { Billing } from "../views/Billing";
import { Matches } from "../views/Matches";
import { Overview } from "../views/Overview";
import { Seasons } from "../views/Seasons";
import { Teams } from "../views/Teams";
import { Training } from "../views/Training";

const TITLES: Record<View, string> = {
  overview: "Visão geral",
  athletes: "Atletas",
  teams: "Equipas",
  seasons: "Épocas e escalões",
  training: "Treinos",
  matches: "Jogos e convocatórias",
  billing: "Quotas e financeiro"
};

/** Estrutura da aplicação autenticada: menu lateral, barra superior, ecrã ativo e notificações. */
export function Shell({ onLogout }: { onLogout: () => void }) {
  const [view, setView] = useState<View>("overview");
  const [roles, setRoles] = useState<Role[]>([]);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);

  useEffect(() => {
    api
      .me()
      .then(me => setRoles(me.roles))
      .catch(onLogout);
  }, [onLogout]);

  useEffect(() => {
    if (toast) {
      const timer = setTimeout(() => setToast(null), 4200);
      return () => clearTimeout(timer);
    }
  }, [toast]);

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
          {view === "athletes" && <Athletes showToast={setToast} />}
          {view === "teams" && <Teams showToast={setToast} canManage={canManage} />}
          {view === "seasons" && <Seasons showToast={setToast} canManage={canManage} />}
          {view === "training" && <Training showToast={setToast} canManage={canManage} />}
          {view === "matches" && <Matches showToast={setToast} canManage={canManage} />}
          {view === "billing" && <Billing showToast={setToast} canManage={canManageBilling} />}
        </div>
      </main>
      {toast && (
        <div className={`toast ${toast.kind}`}>
          <span>{toast.kind === "success" ? "✓" : "!"}</span>
          {toast.message}
        </div>
      )}
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
