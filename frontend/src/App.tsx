import { useCallback, useEffect, useState } from "react";
import {
  Activity, BookOpen, CalendarDays, ChevronRight, CircleUserRound, CreditCard, FileText,
  LayoutDashboard, LogOut, Menu, Plus, Search, ShieldCheck, Sparkles, Swords, Users, X
} from "lucide-react";
import { api, authStore, type AgeGroup, type Athlete, type Charge, type FeePlan, type Match, type Role, type Season, type Team, type TrainingSession } from "./api";

type View = "overview" | "athletes" | "teams" | "seasons" | "training" | "matches" | "billing";
type Toast = { kind: "success" | "error"; message: string };

export function App() {
  const [authenticated, setAuthenticated] = useState(Boolean(authStore.access));
  const [sessionMessage, setSessionMessage] = useState("");
  const logout = useCallback(() => { authStore.clear(); setAuthenticated(false); }, []);
  useEffect(() => {
    const handleExpired = () => { setSessionMessage("A tua sessão terminou. Entra novamente para continuar."); setAuthenticated(false); };
    window.addEventListener("gestao:session-expired", handleExpired);
    return () => window.removeEventListener("gestao:session-expired", handleExpired);
  }, []);
  if (!authenticated) return <Login onLogin={() => { setSessionMessage(""); setAuthenticated(true); }} message={sessionMessage} />;
  return <Shell onLogout={logout} />;
}

function Login({ onLogin, message }: { onLogin: () => void; message?: string }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const tenant = window.location.hostname.split(".")[0] === "localhost" ? "" : window.location.hostname.split(".")[0];
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: React.FormEvent) {
    event.preventDefault(); setLoading(true); setError("");
    try { authStore.save(await api.login(email, password)); onLogin(); }
    catch { setError("Não foi possível iniciar sessão. Confirma o clube, email e password."); }
    finally { setLoading(false); }
  }
  return <main className="login-page">
    <section className="login-art">
      <div className="brand brand-light"><span className="brand-mark">V</span><span>Clube<span className="brand-dot">.</span></span></div>
      <div className="login-message"><p className="eyebrow">GESTÃO DESPORTIVA</p><h1>Mais tempo para<br /><em>jogar.</em></h1><p>O teu clube, organizado num só lugar.</p></div>
      <div className="ball-decoration">◌</div>
    </section>
    <section className="login-panel">
      <div className="login-card">
        <div className="brand"><span className="brand-mark">V</span><span>Clube<span className="brand-dot">.</span></span></div>
        <div className="login-heading"><p className="eyebrow">BEM-VINDO DE VOLTA</p><h2>Entrar na tua conta</h2><p>Acede ao espaço de gestão do teu clube.</p></div>
        <form onSubmit={submit} className="stack">
          <label>Clube<input value={tenant || "subdominio-do-clube"} readOnly aria-describedby="tenant-help" /><small id="tenant-help" className="field-help">{tenant ? `Aceder via ${tenant}.localhost` : "Abre esta página através do subdomínio do teu clube (ex.: clube-piloto.localhost:5173)."}</small></label>
          <label>Email<input type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="nome@clube.pt" required /></label>
          <label>Password<input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="••••••••" required /></label>
          {(error || message) && <p className="error-text">{error || message}</p>}
          <button className="button button-primary button-wide" disabled={loading}>{loading ? "A entrar..." : "Entrar"} <ChevronRight size={17} /></button>
        </form>
        <p className="login-note"><ShieldCheck size={15} /> Os teus dados estão protegidos e isolados por clube.</p>
      </div>
    </section>
  </main>;
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const [view, setView] = useState<View>("overview");
  const [roles, setRoles] = useState<Role[]>([]);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);
  useEffect(() => { api.me().then(me => setRoles(me.roles)).catch(onLogout); }, [onLogout]);
  useEffect(() => { if (toast) { const timer = setTimeout(() => setToast(null), 4200); return () => clearTimeout(timer); } }, [toast]);
  const canManage = roles.some(role => role === "ADMIN" || role === "DIRECAO");
  return <div className="app-shell">
    <aside className={`sidebar ${mobileOpen ? "is-open" : ""}`}>
      <div className="sidebar-head"><div className="brand brand-light"><span className="brand-mark">V</span><span>Clube<span className="brand-dot">.</span></span></div><button className="icon-button mobile-only" onClick={() => setMobileOpen(false)}><X size={20} /></button></div>
      <div className="club-switcher"><div className="club-avatar">SC</div><div><strong>Sport Club</strong><small>Clube principal</small></div><ChevronRight size={16} /></div>
      <nav className="nav">
        <p className="nav-label">PRINCIPAL</p>
        <NavItem active={view === "overview"} onClick={() => { setView("overview"); setMobileOpen(false); }} icon={<LayoutDashboard size={18} />} label="Visão geral" />
        <NavItem active={view === "athletes"} onClick={() => { setView("athletes"); setMobileOpen(false); }} icon={<Users size={18} />} label="Atletas" />
        <NavItem active={view === "teams"} onClick={() => { setView("teams"); setMobileOpen(false); }} icon={<Activity size={18} />} label="Equipas" />
        <NavItem active={view === "seasons"} onClick={() => { setView("seasons"); setMobileOpen(false); }} icon={<BookOpen size={18} />} label="Épocas e escalões" />
        <NavItem active={view === "training"} onClick={() => { setView("training"); setMobileOpen(false); }} icon={<CalendarDays size={18} />} label="Treinos" />
        <NavItem active={view === "matches"} onClick={() => { setView("matches"); setMobileOpen(false); }} icon={<Swords size={18} />} label="Jogos e convocatórias" />
        <p className="nav-label nav-space">CLUBE</p>
        <NavItem active={view === "billing"} onClick={() => { setView("billing"); setMobileOpen(false); }} icon={<CreditCard size={18} />} label="Quotas e financeiro" />
        <NavItem disabled icon={<FileText size={18} />} label="Documentos" />
        <NavItem disabled icon={<Sparkles size={18} />} label="Definições" />
      </nav>
      <div className="sidebar-bottom"><div className="user-mini"><div className="user-avatar">IR</div><div><strong>Administrador</strong><small>{roles.join(" · ") || "Membro"}</small></div></div><button className="logout-button" onClick={onLogout} aria-label="Terminar sessão"><LogOut size={18} /></button></div>
    </aside>
    {mobileOpen && <button className="sidebar-overlay mobile-only" onClick={() => setMobileOpen(false)} aria-label="Fechar menu" />}
    <main className="main-content">
      <header className="topbar"><button className="icon-button mobile-only" onClick={() => setMobileOpen(true)}><Menu size={21} /></button><div className="breadcrumb"><span>Sport Club</span><ChevronRight size={15} /><strong>{view === "overview" ? "Visão geral" : view === "athletes" ? "Atletas" : view === "teams" ? "Equipas" : view === "seasons" ? "Épocas e escalões" : view === "training" ? "Treinos" : view === "matches" ? "Jogos e convocatórias" : "Quotas e financeiro"}</strong></div><div className="topbar-actions"><span className="status-dot">Sistema operacional</span><CircleUserRound size={21} /></div></header>
      <div className="content-wrap">{view === "overview" && <Overview onNavigate={setView} canManage={canManage} />} {view === "athletes" && <Athletes showToast={setToast} />} {view === "teams" && <Teams showToast={setToast} canManage={canManage} />} {view === "seasons" && <Seasons showToast={setToast} canManage={canManage} />} {view === "training" && <Training showToast={setToast} canManage={canManage} />} {view === "matches" && <Matches showToast={setToast} canManage={canManage} />} {view === "billing" && <Billing showToast={setToast} canManage={canManage} />}</div>
    </main>
    {toast && <div className={`toast ${toast.kind}`}><span>{toast.kind === "success" ? "✓" : "!"}</span>{toast.message}</div>}
  </div>;
}

function NavItem({ icon, label, active, disabled, onClick }: { icon: React.ReactNode; label: string; active?: boolean; disabled?: boolean; onClick?: () => void }) {
  return <button className={`nav-item ${active ? "active" : ""}`} disabled={disabled} onClick={onClick}>{icon}<span>{label}</span>{active && <span className="nav-active-mark" />}</button>;
}

function Overview({ onNavigate, canManage }: { onNavigate: (view: View) => void; canManage: boolean }) {
  const [stats, setStats] = useState({ athletes: 0, teams: 0, seasons: 0 });
  useEffect(() => { Promise.all([api.athletes({ size: 1 }), api.teams(), api.seasons()]).then(([a, t, s]) => setStats({ athletes: a.total, teams: t.length, seasons: s.length })).catch(() => undefined); }, []);
  return <section><div className="page-heading"><div><p className="eyebrow">SÁBADO, 14 NOVEMBRO 2026</p><h1>Bom dia, Ivo <span className="wave">👋</span></h1><p className="muted">Aqui está o que se passa no teu clube.</p></div><div className="heading-actions"><button className="button button-secondary" onClick={() => onNavigate("athletes")}><Plus size={16} /> Adicionar atleta</button></div></div>
    <div className="welcome-card"><div><p className="eyebrow light-eyebrow">A TUA EQUIPA, O TEU JOGO</p><h2>Um clube mais simples<br />começa aqui.</h2><p>Organiza atletas, equipas e épocas sem perder tempo em folhas de cálculo.</p><button className="button button-light" onClick={() => onNavigate("athletes")}>Explorar atletas <ChevronRight size={16} /></button></div><div className="welcome-graphic"><div className="graphic-ball">V</div><div className="graphic-ring ring-one" /><div className="graphic-ring ring-two" /></div></div>
    <div className="section-title"><h2>Resumo do clube</h2><span>Atualizado agora</span></div><div className="stat-grid"><Stat icon={<Users />} label="Atletas" value={stats.athletes} color="orange" onClick={() => onNavigate("athletes")} /><Stat icon={<Activity />} label="Equipas" value={stats.teams} color="blue" onClick={() => onNavigate("teams")} /><Stat icon={<BookOpen />} label="Épocas" value={stats.seasons} color="green" onClick={() => onNavigate("seasons")} /><Stat icon={<ShieldCheck />} label="Estado" value="Seguro" text color="purple" /></div>
    <div className="two-col"><div className="panel"><div className="panel-heading"><h3>Começa por aqui</h3></div><div className="quick-list"><Quick icon={<Users />} title="Adiciona os teus atletas" text="Cria fichas ou importa um CSV." onClick={() => onNavigate("athletes")} done={stats.athletes > 0} /><Quick icon={<Activity />} title="Organiza as equipas" text="Liga épocas, escalões e plantéis." onClick={() => onNavigate("teams")} done={stats.teams > 0} /><Quick icon={<BookOpen />} title="Define a época" text="Configura datas e escalões." onClick={() => onNavigate("seasons")} done={stats.seasons > 0} /></div></div><div className="panel panel-note"><Sparkles size={22} /><h3>Feito para o teu clube</h3><p>Os dados estão isolados, seguros e disponíveis para toda a equipa — cada pessoa vê apenas o que precisa.</p>{canManage && <span className="tag tag-green">Acesso de gestão ativo</span>}</div></div>
  </section>;
}

function Stat({ icon, label, value, color, text, onClick }: { icon: React.ReactNode; label: string; value: number | string; color: string; text?: boolean; onClick?: () => void }) {
  return <button className={`stat-card ${color}`} onClick={onClick}><span className="stat-icon">{icon}</span><span className="stat-label">{label}</span><strong className={text ? "stat-text" : ""}>{value}</strong><ChevronRight className="stat-arrow" size={17} /></button>;
}
function Quick({ icon, title, text, done, onClick }: { icon: React.ReactNode; title: string; text: string; done: boolean; onClick: () => void }) {
  return <button className="quick-item" onClick={onClick}><span className="quick-icon">{icon}</span><span><strong>{title}</strong><small>{text}</small></span><span className={`check ${done ? "done" : ""}`}>{done ? "✓" : "→"}</span></button>;
}

function Athletes({ showToast }: { showToast: (toast: Toast) => void }) {
  const [athletes, setAthletes] = useState<Athlete[]>([]); const [total, setTotal] = useState(0); const [query, setQuery] = useState(""); const [loading, setLoading] = useState(true); const [modal, setModal] = useState<"create" | "import" | null>(null); const [selected, setSelected] = useState<Athlete | null>(null);
  async function load() { setLoading(true); try { const page = await api.athletes({ q: query || undefined, size: 100 }); setAthletes(page.items); setTotal(page.total); } catch { showToast({ kind: "error", message: "Não foi possível carregar os atletas." }); } finally { setLoading(false); } }
  useEffect(() => { const timer = setTimeout(load, 250); return () => clearTimeout(timer); }, [query]);
  return <section><PageHeader eyebrow="PLANTEL" title="Atletas" description={`${total} atletas registados no clube.`} action={<><button className="button button-secondary" onClick={() => setModal("import")}><FileText size={16} /> Importar CSV</button><button className="button button-primary" onClick={() => setModal("create")}><Plus size={16} /> Novo atleta</button></>} />
    <div className="toolbar"><div className="search-box"><Search size={17} /><input placeholder="Pesquisar por nome..." value={query} onChange={e => setQuery(e.target.value)} /></div><span className="toolbar-count">{total} resultados</span></div>
    <div className="panel table-panel">{loading ? <Loading /> : athletes.length === 0 ? <Empty icon={<Users />} title="Ainda não há atletas" text="Adiciona o primeiro atleta ou importa o plantel em CSV." /> : <table><thead><tr><th>Atleta</th><th>Data de nascimento</th><th>Licença</th><th>Estado</th><th /></tr></thead><tbody>{athletes.map(a => <tr key={a.id} className="clickable-row" onClick={() => setSelected(a)}><td><div className="person-cell"><span className="person-avatar">{a.fullName.split(" ").map(p => p[0]).slice(0, 2).join("")}</span><span><strong>{a.fullName}</strong><small>{a.email || "Sem email"}</small></span></div></td><td>{formatDate(a.birthDate)}</td><td>{a.licenseNumber || "—"}</td><td><span className={`tag ${a.status === "ACTIVE" ? "tag-green" : "tag-gray"}`}>{a.status === "ACTIVE" ? "Ativo" : "Inativo"}</span></td><td><ChevronRight size={17} className="row-arrow" /></td></tr>)}</tbody></table>}</div>
    {modal === "create" && <AthleteModal onClose={() => setModal(null)} onSaved={() => { setModal(null); load(); showToast({ kind: "success", message: "Atleta criado com sucesso." }); }} onError={message => showToast({ kind: "error", message })} />}
    {modal === "import" && <ImportModal onClose={() => setModal(null)} onImported={count => { setModal(null); load(); showToast({ kind: "success", message: `${count} atletas importados com sucesso.` }); }} onError={message => showToast({ kind: "error", message })} />}
    {selected && <AthleteDetails athlete={selected} onClose={() => setSelected(null)} onDeleted={() => { setSelected(null); load(); showToast({ kind: "success", message: "Atleta removido com sucesso." }); }} onError={message => showToast({ kind: "error", message })} />}
  </section>;
}

function AthleteDetails({ athlete, onClose, onDeleted, onError }: { athlete: Athlete; onClose: () => void; onDeleted: () => void; onError: (message: string) => void }) {
  const [deleting, setDeleting] = useState(false);
  async function remove() {
   if (!window.confirm(`Remover ${athlete.fullName}? Esta ação anonimiza os dados do atleta.`)) return;
   setDeleting(true);
   try { await api.deleteAthlete(athlete.id); onDeleted(); } catch { onError("Não foi possível remover o atleta."); } finally { setDeleting(false); }
  }
  return <Modal title={athlete.fullName} onClose={onClose}><div className="detail-list"><p><strong>Email</strong><span>{athlete.email || "—"}</span></p><p><strong>Data de nascimento</strong><span>{formatDate(athlete.birthDate)}</span></p><p><strong>Licença</strong><span>{athlete.licenseNumber || "—"}</span></p><p><strong>Estado</strong><span>{athlete.status === "ACTIVE" ? "Ativo" : "Inativo"}</span></p></div><div className="modal-actions"><button type="button" className="button button-secondary" onClick={onClose}>Fechar</button><button type="button" className="button button-danger" disabled={deleting} onClick={remove}>{deleting ? "A remover..." : "Remover atleta"}</button></div></Modal>;
}

function Teams({ showToast, canManage }: { showToast: (toast: Toast) => void; canManage: boolean }) {
  const [teams, setTeams] = useState<Team[]>([]); const [seasons, setSeasons] = useState<Season[]>([]); const [modal, setModal] = useState(false); const [selected, setSelected] = useState<Team | null>(null);
  async function load() { try { setTeams(await api.teams()); setSeasons(await api.seasons()); } catch { showToast({ kind: "error", message: "Não foi possível carregar as equipas." }); } }
  useEffect(() => { load(); }, []);
  return <section><PageHeader eyebrow="ORGANIZAÇÃO" title="Equipas" description="As equipas e os seus plantéis." action={canManage ? <button className="button button-primary" onClick={() => setModal(true)}><Plus size={16} /> Nova equipa</button> : undefined} />
    <div className="cards-grid">{teams.map(team => <button className="team-card panel team-card-button" key={team.id} onClick={() => setSelected(team)}><div className="team-cover"><span className="team-symbol">V</span><span className={`tag ${team.status === "ACTIVE" ? "tag-green" : "tag-gray"}`}>{team.status === "ACTIVE" ? "Ativa" : "Inativa"}</span></div><div className="team-body"><h3>{team.name}</h3><p>{seasons.find(s => s.id === team.seasonId)?.name || "Sem época"} <span>·</span> {team.ageGroupId ? "Escalão definido" : "Sem escalão"}</p><div className="team-footer"><span><Users size={15} /> Ver plantel</span><ChevronRight size={17} /></div></div></button>)}{teams.length === 0 && <div className="panel full-card"><Empty icon={<Activity />} title="Ainda não há equipas" text="Cria uma equipa para começar a organizar o plantel." /></div>}</div>
    {modal && <TeamModal seasons={seasons} onClose={() => setModal(false)} onSaved={() => { setModal(false); load(); showToast({ kind: "success", message: "Equipa criada com sucesso." }); }} onError={message => showToast({ kind: "error", message })} />}
    {selected && <TeamDetails team={selected} season={seasons.find(s => s.id === selected.seasonId)} onClose={() => setSelected(null)} onError={message => showToast({ kind: "error", message })} />}
  </section>;
}

function TeamDetails({ team, season, onClose, onError }: { team: Team; season?: Season; onClose: () => void; onError: (message: string) => void }) {
  const [roster, setRoster] = useState<{ athleteId: string; fullName: string; status: string }[]>([]);
  useEffect(() => { api.teamAthletes(team.id).then(setRoster).catch(() => onError("Não foi possível carregar o plantel.")); }, [team.id]);
  return <Modal title={team.name} onClose={onClose}><p className="muted">{season?.name || "Sem época"} · {team.status === "ACTIVE" ? "Ativa" : "Inativa"}</p><div className="detail-list">{roster.length === 0 ? <Empty icon={<Users />} title="Plantel vazio" text="Ainda não existem atletas associados a esta equipa." /> : roster.map(athlete => <p key={athlete.athleteId}><strong>{athlete.fullName}</strong><span>{athlete.status === "ACTIVE" ? "Ativo" : "Inativo"}</span></p>)}</div><div className="modal-actions"><button type="button" className="button button-secondary" onClick={onClose}>Fechar</button></div></Modal>;
}

function Seasons({ showToast, canManage }: { showToast: (toast: Toast) => void; canManage: boolean }) {
  const [seasons, setSeasons] = useState<Season[]>([]); const [groups, setGroups] = useState<AgeGroup[]>([]); const [selectedSeasonId, setSelectedSeasonId] = useState(""); const [modal, setModal] = useState<"season" | "group" | null>(null);
  async function load() { try { const [s, g] = await Promise.all([api.seasons(), api.ageGroups()]); setSeasons(s); setGroups(g); } catch { showToast({ kind: "error", message: "Não foi possível carregar as épocas." }); } }
  useEffect(() => { load(); }, []); useEffect(() => { if (!selectedSeasonId && seasons[0]) setSelectedSeasonId(seasons[0].id); }, [seasons, selectedSeasonId]);
  const selectedGroups = groups.filter(group => group.seasonId === selectedSeasonId);
  return <section><PageHeader eyebrow="CONFIGURAÇÃO" title="Épocas e escalões" description="A estrutura desportiva do teu clube." action={canManage ? <><button className="button button-secondary" onClick={() => setModal("group")}><Plus size={16} /> Novo escalão</button><button className="button button-primary" onClick={() => setModal("season")}><Plus size={16} /> Nova época</button></> : undefined} />
   <div className="season-selector"><label>Época<select value={selectedSeasonId} onChange={event => setSelectedSeasonId(event.target.value)}>{seasons.map(season => <option key={season.id} value={season.id}>{season.name}{season.isActive ? " · ativa" : ""}</option>)}</select></label></div>
   {seasons.length > 0 && <div className="panel season-summary"><div className="season-icon"><BookOpen size={21} /></div><div className="season-info"><h3>{seasons.find(season => season.id === selectedSeasonId)?.name}</h3><p>{seasons.find(season => season.id === selectedSeasonId)?.startDate ? formatDate(seasons.find(season => season.id === selectedSeasonId)?.startDate) : "Sem início"} · {selectedGroups.length} escalões</p></div></div>}
   <div className="group-list">{selectedGroups.map(group => <div className="panel group-card" key={group.id}><div className="season-icon"><Users size={19} /></div><div className="season-info"><h3>{group.name}</h3><p>Nascidos entre {group.minBirthYear} e {group.maxBirthYear}</p></div><ChevronRight size={19} className="row-arrow" /></div>)}{seasons.length > 0 && selectedGroups.length === 0 && <div className="panel full-card"><Empty icon={<Users />} title="Sem escalões nesta época" text="Adiciona o primeiro escalão para esta época." /></div>}{seasons.length === 0 && <div className="panel full-card"><Empty icon={<BookOpen />} title="Ainda não há épocas" text="Define a primeira época desportiva do clube." /></div>}</div>
    {modal === "season" && <SeasonModal onClose={() => setModal(null)} onSaved={() => { setModal(null); load(); showToast({ kind: "success", message: "Época criada com sucesso." }); }} onError={message => showToast({ kind: "error", message })} />}
    {modal === "group" && <GroupModal seasons={seasons} onClose={() => setModal(null)} onSaved={() => { setModal(null); load(); showToast({ kind: "success", message: "Escalão criado com sucesso." }); }} onError={message => showToast({ kind: "error", message })} />}
  </section>;
}

function Training({ showToast, canManage }: { showToast: (toast: Toast) => void; canManage: boolean }) {
  const [sessions, setSessions] = useState<TrainingSession[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [modal, setModal] = useState(false);
  async function load() {
    try { const [items, clubTeams] = await Promise.all([api.trainingSessions(), api.teams()]); setSessions(items); setTeams(clubTeams); }
    catch { showToast({ kind: "error", message: "Não foi possível carregar o calendário de treinos." }); }
  }
  useEffect(() => { load(); }, []);
  return <section><PageHeader eyebrow="CALENDÁRIO" title="Treinos" description="Planeia sessões, espaços e presenças." action={canManage ? <button className="button button-primary" onClick={() => setModal(true)}><Plus size={16} /> Novo treino</button> : undefined} />
    <div className="panel table-panel"><table><thead><tr><th>Data e hora</th><th>Equipa</th><th>Local</th><th>Estado</th></tr></thead><tbody>{sessions.sort((a, b) => a.startTime.localeCompare(b.startTime)).map(session => <tr key={session.id}><td><strong>{formatDateTime(session.startTime)}</strong><small className="table-subtext">{formatTime(session.startTime)} – {formatTime(session.endTime)}</small></td><td>{teams.find(t => t.id === session.teamId)?.name || "Equipa"}</td><td>{session.courtId ? "Espaço reservado" : "Por definir"}</td><td><span className="tag tag-green">{session.status === "CANCELLED" ? "Cancelado" : "Agendado"}</span></td></tr>)}</tbody></table>{sessions.length === 0 && <Empty icon={<CalendarDays />} title="Calendário vazio" text="Cria o primeiro treino da época para começar a controlar presenças." />}</div>
    {modal && <TrainingModal teams={teams} onClose={() => setModal(false)} onSaved={() => { setModal(false); load(); showToast({ kind: "success", message: "Treino agendado com sucesso." }); }} onError={message => showToast({ kind: "error", message })} />}
  </section>;
}

function Matches({ showToast, canManage }: { showToast: (toast: Toast) => void; canManage: boolean }) {
  const [matches, setMatches] = useState<Match[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [modal, setModal] = useState(false);
  async function load() {
    try { const [items, clubTeams] = await Promise.all([api.matches(), api.teams()]); setMatches(items); setTeams(clubTeams); }
    catch { showToast({ kind: "error", message: "Não foi possível carregar os jogos." }); }
  }
  useEffect(() => { load(); }, []);
  return <section><PageHeader eyebrow="COMPETIÇÃO" title="Jogos e convocatórias" description="Acompanha resultados e respostas do plantel." action={canManage ? <button className="button button-primary" onClick={() => setModal(true)}><Plus size={16} /> Novo jogo</button> : undefined} />
    <div className="cards-grid">{matches.map(match => <div className="panel match-card" key={match.id}><div className="match-date"><span>{formatDate(match.matchDate.slice(0, 10))}</span><strong>{formatTime(match.matchDate)}</strong></div><div className="match-main"><span className="eyebrow">{match.competition || "JOGO"}</span><h3>{match.isHome ? "Sport Club" : match.opponent} <small>vs</small> {match.isHome ? match.opponent : "Sport Club"}</h3><p>{teams.find(t => t.id === match.teamId)?.name || "Equipa"} · {match.location || "Local por definir"}</p></div><span className={`tag ${match.status === "PLAYED" ? "tag-gray" : "tag-green"}`}>{match.status === "PLAYED" ? "Terminado" : "Agendado"}</span></div>)}{matches.length === 0 && <div className="panel full-card"><Empty icon={<Swords />} title="Sem jogos marcados" text="Agenda o próximo jogo e trata da convocatória num só lugar." /></div>}</div>
    {modal && <MatchModal teams={teams} onClose={() => setModal(false)} onSaved={() => { setModal(false); load(); showToast({ kind: "success", message: "Jogo criado com sucesso." }); }} onError={message => showToast({ kind: "error", message })} />}
  </section>;
}

function Billing({ showToast, canManage }: { showToast: (toast: Toast) => void; canManage: boolean }) {
  const [plans, setPlans] = useState<FeePlan[]>([]);
  const [charges, setCharges] = useState<Charge[]>([]);
  const [modal, setModal] = useState(false);
  async function load() {
    try { const [feePlans, currentCharges] = await Promise.all([api.feePlans(), api.charges()]); setPlans(feePlans); setCharges(currentCharges); }
    catch { showToast({ kind: "error", message: "Não foi possível carregar o financeiro." }); }
  }
  useEffect(() => { load(); }, []);
  const pending = charges.filter(charge => charge.status === "PENDING" || charge.status === "OVERDUE");
  const received = charges.filter(charge => charge.status === "PAID").reduce((total, charge) => total + charge.amountCents, 0);
  return <section><PageHeader eyebrow="FINANCEIRO" title="Quotas e financeiro" description="Planos, cobranças e mapa de dívidas do clube." action={canManage ? <button className="button button-primary" onClick={() => setModal(true)}><Plus size={16} /> Novo plano</button> : undefined} />
    <div className="stat-grid billing-stats"><Stat icon={<CreditCard />} label="Planos ativos" value={plans.length} color="blue" /><Stat icon={<Activity />} label="Cobranças pendentes" value={pending.length} color="orange" /><Stat icon={<ShieldCheck />} label="Recebido" value={formatMoney(received)} color="green" text /></div>
    <div className="panel table-panel"><div className="panel-heading"><h3>Cobranças recentes</h3><span className="muted">{charges.length} registos</span></div><table><thead><tr><th>Período</th><th>Vencimento</th><th>Valor</th><th>Estado</th></tr></thead><tbody>{charges.slice(0, 12).map(charge => <tr key={charge.id}><td>{charge.billingPeriod || "—"}</td><td>{formatDate(charge.dueDate)}</td><td><strong>{formatMoney(charge.amountCents)}</strong></td><td><span className={`tag ${charge.status === "PAID" ? "tag-green" : "tag-gray"}`}>{charge.status === "PAID" ? "Paga" : charge.status === "OVERDUE" ? "Em atraso" : "Pendente"}</span></td></tr>)}</tbody></table>{charges.length === 0 && <Empty icon={<CreditCard />} title="Ainda não há cobranças" text="Cria um plano de quotas para gerar a primeira cobrança mensal." />}</div>
    {modal && <FeePlanModal onClose={() => setModal(false)} onSaved={() => { setModal(false); load(); showToast({ kind: "success", message: "Plano de quotas criado." }); }} onError={message => showToast({ kind: "error", message })} />}
  </section>;
}

function PageHeader({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: React.ReactNode }) { return <div className="page-heading"><div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1><p className="muted">{description}</p></div><div className="heading-actions">{action}</div></div>; }
function Loading() { return <div className="empty-state"><div className="loader" /><p>A carregar...</p></div>; }
function Empty({ icon, title, text }: { icon: React.ReactNode; title: string; text: string }) { return <div className="empty-state"><div className="empty-icon">{icon}</div><h3>{title}</h3><p>{text}</p></div>; }
function Modal({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) { return <div className="modal-backdrop" onMouseDown={e => e.target === e.currentTarget && onClose()}><div className="modal"><div className="modal-head"><div><p className="eyebrow">CLUBE</p><h2>{title}</h2></div><button className="icon-button" onClick={onClose}><X size={20} /></button></div>{children}</div></div>; }
function TrainingModal({ teams, onClose, onSaved, onError }: { teams: Team[]; onClose: () => void; onSaved: () => void; onError: (message: string) => void }) {
  const [data, setData] = useState({ teamId: teams[0]?.id ?? "", startTime: "", endTime: "", recurrence: "", notes: "" }); const [saving, setSaving] = useState(false);
  async function submit(e: React.FormEvent) { e.preventDefault(); setSaving(true); try { await api.createTrainingSession({ ...data, startTime: new Date(data.startTime).toISOString(), endTime: new Date(data.endTime).toISOString(), status: "ACTIVE" }); onSaved(); } catch { onError("Não foi possível agendar o treino."); } finally { setSaving(false); } }
  return <Modal title="Novo treino" onClose={onClose}><form className="stack" onSubmit={submit}><label>Equipa<select required value={data.teamId} onChange={e => setData({ ...data, teamId: e.target.value })}>{teams.map(team => <option key={team.id} value={team.id}>{team.name}</option>)}</select></label><div className="form-grid"><label>Início<input required type="datetime-local" value={data.startTime} onChange={e => setData({ ...data, startTime: e.target.value })} /></label><label>Fim<input required type="datetime-local" value={data.endTime} onChange={e => setData({ ...data, endTime: e.target.value })} /></label></div><label>Repetição<select value={data.recurrence} onChange={e => setData({ ...data, recurrence: e.target.value })}><option value="">Não repetir</option><option value="WEEKLY">Semanal</option><option value="BIWEEKLY">Quinzenal</option></select><small className="field-help">A recorrência fica associada à marcação para o calendário do clube.</small></label><label>Notas<input placeholder="Pavilhão, foco da sessão..." value={data.notes} onChange={e => setData({ ...data, notes: e.target.value })} /></label><ModalActions saving={saving} onClose={onClose} /></form></Modal>;
}

function MatchModal({ teams, onClose, onSaved, onError }: { teams: Team[]; onClose: () => void; onSaved: () => void; onError: (message: string) => void }) {
  const [data, setData] = useState({ teamId: teams[0]?.id ?? "", opponent: "", competition: "", location: "", matchDate: "", isHome: "true" }); const [saving, setSaving] = useState(false);
  async function submit(e: React.FormEvent) { e.preventDefault(); setSaving(true); try { await api.createMatch({ ...data, matchDate: new Date(data.matchDate).toISOString(), isHome: data.isHome === "true", status: "SCHEDULED" }); onSaved(); } catch { onError("Não foi possível criar o jogo."); } finally { setSaving(false); } }
  return <Modal title="Novo jogo" onClose={onClose}><form className="stack" onSubmit={submit}><div className="form-grid"><label>Equipa<select required value={data.teamId} onChange={e => setData({ ...data, teamId: e.target.value })}>{teams.map(team => <option key={team.id} value={team.id}>{team.name}</option>)}</select></label><label>Adversário<input required value={data.opponent} onChange={e => setData({ ...data, opponent: e.target.value })} /></label></div><div className="form-grid"><label>Competição<input placeholder="Campeonato Nacional" value={data.competition} onChange={e => setData({ ...data, competition: e.target.value })} /></label><label>Local<input value={data.location} onChange={e => setData({ ...data, location: e.target.value })} /></label></div><div className="form-grid"><label>Data e hora<input required type="datetime-local" value={data.matchDate} onChange={e => setData({ ...data, matchDate: e.target.value })} /></label><label>Jogo<select value={data.isHome} onChange={e => setData({ ...data, isHome: e.target.value })}><option value="true">Em casa</option><option value="false">Fora</option></select></label></div><ModalActions saving={saving} onClose={onClose} /></form></Modal>;
}

function FeePlanModal({ onClose, onSaved, onError }: { onClose: () => void; onSaved: () => void; onError: (message: string) => void }) {
  const [data, setData] = useState({ name: "", amount: "", periodicity: "MONTHLY" }); const [saving, setSaving] = useState(false);
  async function submit(e: React.FormEvent) { e.preventDefault(); setSaving(true); try { await api.createFeePlan({ name: data.name, amountCents: Math.round(Number(data.amount) * 100), periodicity: data.periodicity }); onSaved(); } catch { onError("Não foi possível criar o plano de quotas."); } finally { setSaving(false); } }
  return <Modal title="Novo plano de quotas" onClose={onClose}><form className="stack" onSubmit={submit}><label>Nome do plano<input required placeholder="Mensalidade Sub-16" value={data.name} onChange={e => setData({ ...data, name: e.target.value })} /></label><div className="form-grid"><label>Valor (€)<input required min="0" step="0.01" type="number" value={data.amount} onChange={e => setData({ ...data, amount: e.target.value })} /></label><label>Periodicidade<select value={data.periodicity} onChange={e => setData({ ...data, periodicity: e.target.value })}><option value="MONTHLY">Mensal</option><option value="ANNUAL">Anual</option><option value="ONE_OFF">Única</option></select></label></div><ModalActions saving={saving} onClose={onClose} /></form></Modal>;
}

function AthleteModal({ onClose, onSaved, onError }: { onClose: () => void; onSaved: () => void; onError: (message: string) => void }) { const [data, setData] = useState({ fullName: "", birthDate: "", gender: "F", email: "", phone: "", licenseNumber: "" }); const [saving, setSaving] = useState(false); const update = (key: string, value: string) => setData(prev => ({ ...prev, [key]: value })); async function submit(e: React.FormEvent) { e.preventDefault(); setSaving(true); try { await api.createAthlete({ ...data, status: "ACTIVE" }); onSaved(); } catch { onError("Não foi possível criar o atleta."); } finally { setSaving(false); } } return <Modal title="Novo atleta" onClose={onClose}><form className="stack" onSubmit={submit}><div className="form-grid"><label>Nome completo<input required value={data.fullName} onChange={e => update("fullName", e.target.value)} /></label><label>Data de nascimento<input required type="date" value={data.birthDate} onChange={e => update("birthDate", e.target.value)} /></label><label>Género<select value={data.gender} onChange={e => update("gender", e.target.value)}><option value="F">Feminino</option><option value="M">Masculino</option></select></label><label>Nº de licença<input value={data.licenseNumber} onChange={e => update("licenseNumber", e.target.value)} /></label><label>Email<input type="email" value={data.email} onChange={e => update("email", e.target.value)} /></label><label>Telefone<input value={data.phone} onChange={e => update("phone", e.target.value)} /></label></div><ModalActions saving={saving} onClose={onClose} /></form></Modal>; }
function ImportModal({ onClose, onImported, onError }: { onClose: () => void; onImported: (count: number) => void; onError: (message: string) => void }) { const [file, setFile] = useState<File | null>(null); const [saving, setSaving] = useState(false); async function submit(e: React.FormEvent) { e.preventDefault(); if (!file) return; setSaving(true); try { onImported((await api.importAthletes(file)).imported); } catch { onError("Não foi possível importar o ficheiro CSV."); } finally { setSaving(false); } } return <Modal title="Importar atletas" onClose={onClose}><form className="stack" onSubmit={submit}><div className="upload-box"><FileText size={28} /><strong>{file ? file.name : "Escolhe um ficheiro CSV"}</strong><small>Formato UTF-8 · primeira linha pode conter cabeçalho</small><input type="file" accept=".csv,text/csv" onChange={e => setFile(e.target.files?.[0] ?? null)} /></div><ModalActions saving={saving} disabled={!file} onClose={onClose} submitLabel="Importar" /></form></Modal>; }
function SeasonModal({ onClose, onSaved, onError }: { onClose: () => void; onSaved: () => void; onError: (message: string) => void }) { const [data, setData] = useState({ name: "", startDate: "", endDate: "" }); const [saving, setSaving] = useState(false); async function submit(e: React.FormEvent) { e.preventDefault(); setSaving(true); try { await api.createSeason({ ...data, isActive: true }); onSaved(); } catch { onError("Não foi possível criar a época."); } finally { setSaving(false); } } return <Modal title="Nova época" onClose={onClose}><form className="stack" onSubmit={submit}><label>Nome da época<input required placeholder="2026/27" value={data.name} onChange={e => setData({ ...data, name: e.target.value })} /></label><div className="form-grid"><label>Data de início<input type="date" value={data.startDate} onChange={e => setData({ ...data, startDate: e.target.value })} /></label><label>Data de fim<input type="date" value={data.endDate} onChange={e => setData({ ...data, endDate: e.target.value })} /></label></div><ModalActions saving={saving} onClose={onClose} /></form></Modal>; }
function GroupModal({ seasons, onClose, onSaved, onError }: { seasons: Season[]; onClose: () => void; onSaved: () => void; onError: (message: string) => void }) { const [data, setData] = useState({ name: "", seasonId: seasons[0]?.id ?? "", minBirthYear: "", maxBirthYear: "" }); const [saving, setSaving] = useState(false); async function submit(e: React.FormEvent) { e.preventDefault(); setSaving(true); try { await api.createAgeGroup({ ...data, minBirthYear: Number(data.minBirthYear), maxBirthYear: Number(data.maxBirthYear) }); onSaved(); } catch { onError("Não foi possível criar o escalão."); } finally { setSaving(false); } } return <Modal title="Novo escalão" onClose={onClose}><form className="stack" onSubmit={submit}><label>Nome<input required placeholder="Sub-14" value={data.name} onChange={e => setData({ ...data, name: e.target.value })} /></label><label>Época<select required value={data.seasonId} onChange={e => setData({ ...data, seasonId: e.target.value })}>{seasons.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label><div className="form-grid"><label>Ano mínimo<input required type="number" value={data.minBirthYear} onChange={e => setData({ ...data, minBirthYear: e.target.value })} /></label><label>Ano máximo<input required type="number" value={data.maxBirthYear} onChange={e => setData({ ...data, maxBirthYear: e.target.value })} /></label></div><ModalActions saving={saving} onClose={onClose} /></form></Modal>; }
function TeamModal({ seasons, onClose, onSaved, onError }: { seasons: Season[]; onClose: () => void; onSaved: () => void; onError: (message: string) => void }) { const [data, setData] = useState({ name: "", seasonId: seasons[0]?.id ?? "", status: "ACTIVE" }); const [saving, setSaving] = useState(false); async function submit(e: React.FormEvent) { e.preventDefault(); setSaving(true); try { await api.createTeam(data); onSaved(); } catch { onError("Não foi possível criar a equipa."); } finally { setSaving(false); } } return <Modal title="Nova equipa" onClose={onClose}><form className="stack" onSubmit={submit}><label>Nome da equipa<input required placeholder="Seniores Femininos" value={data.name} onChange={e => setData({ ...data, name: e.target.value })} /></label><label>Época<select value={data.seasonId} onChange={e => setData({ ...data, seasonId: e.target.value })}><option value="">Sem época</option>{seasons.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label><ModalActions saving={saving} onClose={onClose} /></form></Modal>; }
function ModalActions({ saving, disabled, onClose, submitLabel = "Guardar" }: { saving: boolean; disabled?: boolean; onClose: () => void; submitLabel?: string }) { return <div className="modal-actions"><button type="button" className="button button-secondary" onClick={onClose}>Cancelar</button><button className="button button-primary" disabled={saving || disabled}>{saving ? "A guardar..." : submitLabel}</button></div>; }
function formatDate(value?: string) { return value ? new Intl.DateTimeFormat("pt-PT", { dateStyle: "medium" }).format(new Date(`${value}T12:00:00`)) : "—"; }
function formatDateTime(value?: string) { return value ? new Intl.DateTimeFormat("pt-PT", { dateStyle: "medium" }).format(new Date(value)) : "—"; }
function formatTime(value?: string) { return value ? new Intl.DateTimeFormat("pt-PT", { hour: "2-digit", minute: "2-digit" }).format(new Date(value)) : "—"; }
function formatMoney(cents: number) { return new Intl.NumberFormat("pt-PT", { style: "currency", currency: "EUR" }).format(cents / 100); }
