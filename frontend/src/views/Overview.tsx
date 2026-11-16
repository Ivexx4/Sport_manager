import { useEffect, useState } from "react";
import { Activity, BookOpen, ChevronRight, Plus, ShieldCheck, Sparkles, Users } from "lucide-react";
import { api } from "../api";
import { Stat } from "../components/ui";
import type { View } from "../types";

/** Visão geral: indicadores do clube e primeiros passos. */
export function Overview({ onNavigate, canManage }: { onNavigate: (view: View) => void; canManage: boolean }) {
  const [stats, setStats] = useState({ athletes: 0, teams: 0, seasons: 0 });

  useEffect(() => {
    Promise.all([api.athletes({ size: 1 }), api.teams(), api.seasons()])
      .then(([athletes, teams, seasons]) =>
        setStats({ athletes: athletes.total, teams: teams.length, seasons: seasons.length })
      )
      .catch(() => undefined);
  }, []);

  return (
    <section>
      <div className="page-heading">
        <div>
          <p className="eyebrow">SÁBADO, 14 NOVEMBRO 2026</p>
          <h1>
            Bom dia, Ivo <span className="wave">👋</span>
          </h1>
          <p className="muted">Aqui está o que se passa no teu clube.</p>
        </div>
        <div className="heading-actions">
          <button className="button button-secondary" onClick={() => onNavigate("athletes")}>
            <Plus size={16} /> Adicionar atleta
          </button>
        </div>
      </div>
      <div className="welcome-card">
        <div>
          <p className="eyebrow light-eyebrow">A TUA EQUIPA, O TEU JOGO</p>
          <h2>
            Um clube mais simples
            <br />
            começa aqui.
          </h2>
          <p>Organiza atletas, equipas e épocas sem perder tempo em folhas de cálculo.</p>
          <button className="button button-light" onClick={() => onNavigate("athletes")}>
            Explorar atletas <ChevronRight size={16} />
          </button>
        </div>
        <div className="welcome-graphic">
          <div className="graphic-ball">V</div>
          <div className="graphic-ring ring-one" />
          <div className="graphic-ring ring-two" />
        </div>
      </div>
      <div className="section-title">
        <h2>Resumo do clube</h2>
        <span>Atualizado agora</span>
      </div>
      <div className="stat-grid">
        <Stat
          icon={<Users />}
          label="Atletas"
          value={stats.athletes}
          color="orange"
          onClick={() => onNavigate("athletes")}
        />
        <Stat
          icon={<Activity />}
          label="Equipas"
          value={stats.teams}
          color="blue"
          onClick={() => onNavigate("teams")}
        />
        <Stat
          icon={<BookOpen />}
          label="Épocas"
          value={stats.seasons}
          color="green"
          onClick={() => onNavigate("seasons")}
        />
        <Stat icon={<ShieldCheck />} label="Estado" value="Seguro" text color="purple" />
      </div>
      <div className="two-col">
        <div className="panel">
          <div className="panel-heading">
            <h3>Começa por aqui</h3>
          </div>
          <div className="quick-list">
            <Quick
              icon={<Users />}
              title="Adiciona os teus atletas"
              text="Cria fichas ou importa um CSV."
              onClick={() => onNavigate("athletes")}
              done={stats.athletes > 0}
            />
            <Quick
              icon={<Activity />}
              title="Organiza as equipas"
              text="Liga épocas, escalões e plantéis."
              onClick={() => onNavigate("teams")}
              done={stats.teams > 0}
            />
            <Quick
              icon={<BookOpen />}
              title="Define a época"
              text="Configura datas e escalões."
              onClick={() => onNavigate("seasons")}
              done={stats.seasons > 0}
            />
          </div>
        </div>
        <div className="panel panel-note">
          <Sparkles size={22} />
          <h3>Feito para o teu clube</h3>
          <p>
            Os dados estão isolados, seguros e disponíveis para toda a equipa — cada pessoa vê apenas o que precisa.
          </p>
          {canManage && <span className="tag tag-green">Acesso de gestão ativo</span>}
        </div>
      </div>
    </section>
  );
}

function Quick({
  icon,
  title,
  text,
  done,
  onClick
}: {
  icon: React.ReactNode;
  title: string;
  text: string;
  done: boolean;
  onClick: () => void;
}) {
  return (
    <button className="quick-item" onClick={onClick}>
      <span className="quick-icon">{icon}</span>
      <span>
        <strong>{title}</strong>
        <small>{text}</small>
      </span>
      <span className={`check ${done ? "done" : ""}`}>{done ? "✓" : "→"}</span>
    </button>
  );
}
