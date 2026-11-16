import { useEffect, useState } from "react";
import { Plus, Swords } from "lucide-react";
import { api, type Match, type Team } from "../../api";
import { Empty, Modal, ModalActions, PageHeader } from "../../components";
import { formatDate, formatTime } from "../../lib/format";
import type { ReportError, ShowToast } from "../../types";

/** Jogos do clube e respetivas convocatórias. */
export function Matches({ showToast, canManage }: { showToast: ShowToast; canManage: boolean }) {
  const [matches, setMatches] = useState<Match[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [modal, setModal] = useState(false);

  async function load() {
    try {
      const [items, clubTeams] = await Promise.all([api.matches(), api.teams()]);
      setMatches(items);
      setTeams(clubTeams);
    } catch {
      showToast({ kind: "error", message: "Não foi possível carregar os jogos." });
    }
  }

  useEffect(() => {
    load();
  }, []);

  return (
    <section>
      <PageHeader
        eyebrow="COMPETIÇÃO"
        title="Jogos e convocatórias"
        description="Acompanha resultados e respostas do plantel."
        action={
          canManage ? (
            <button className="button button-primary" onClick={() => setModal(true)}>
              <Plus size={16} /> Novo jogo
            </button>
          ) : undefined
        }
      />
      <div className="cards-grid">
        {matches.map(match => (
          <div className="panel match-card" key={match.id}>
            <div className="match-date">
              <span>{formatDate(match.matchDate.slice(0, 10))}</span>
              <strong>{formatTime(match.matchDate)}</strong>
            </div>
            <div className="match-main">
              <span className="eyebrow">{match.competition || "JOGO"}</span>
              <h3>
                {match.isHome ? "Sport Club" : match.opponent} <small>vs</small>{" "}
                {match.isHome ? match.opponent : "Sport Club"}
              </h3>
              <p>
                {teams.find(team => team.id === match.teamId)?.name || "Equipa"} ·{" "}
                {match.location || "Local por definir"}
              </p>
            </div>
            <span className={`tag ${match.status === "PLAYED" ? "tag-gray" : "tag-green"}`}>
              {match.status === "PLAYED" ? "Terminado" : "Agendado"}
            </span>
          </div>
        ))}
        {matches.length === 0 && (
          <div className="panel full-card">
            <Empty
              icon={<Swords />}
              title="Sem jogos marcados"
              text="Agenda o próximo jogo e trata da convocatória num só lugar."
            />
          </div>
        )}
      </div>
      {modal && (
        <MatchModal
          teams={teams}
          onClose={() => setModal(false)}
          onSaved={() => {
            setModal(false);
            load();
            showToast({ kind: "success", message: "Jogo criado com sucesso." });
          }}
          onError={message => showToast({ kind: "error", message })}
        />
      )}
    </section>
  );
}

function MatchModal({
  teams,
  onClose,
  onSaved,
  onError
}: {
  teams: Team[];
  onClose: () => void;
  onSaved: () => void;
  onError: ReportError;
}) {
  const [data, setData] = useState({
    teamId: teams[0]?.id ?? "",
    opponent: "",
    competition: "",
    location: "",
    matchDate: "",
    isHome: "true"
  });
  const [saving, setSaving] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      await api.createMatch({
        ...data,
        matchDate: new Date(data.matchDate).toISOString(),
        isHome: data.isHome === "true",
        status: "SCHEDULED"
      });
      onSaved();
    } catch {
      onError("Não foi possível criar o jogo.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Novo jogo" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <div className="form-grid">
          <label>
            Equipa
            <select required value={data.teamId} onChange={event => setData({ ...data, teamId: event.target.value })}>
              {teams.map(team => (
                <option key={team.id} value={team.id}>
                  {team.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Adversário
            <input
              required
              value={data.opponent}
              onChange={event => setData({ ...data, opponent: event.target.value })}
            />
          </label>
        </div>
        <div className="form-grid">
          <label>
            Competição
            <input
              placeholder="Campeonato Nacional"
              value={data.competition}
              onChange={event => setData({ ...data, competition: event.target.value })}
            />
          </label>
          <label>
            Local
            <input value={data.location} onChange={event => setData({ ...data, location: event.target.value })} />
          </label>
        </div>
        <div className="form-grid">
          <label>
            Data e hora
            <input
              required
              type="datetime-local"
              value={data.matchDate}
              onChange={event => setData({ ...data, matchDate: event.target.value })}
            />
          </label>
          <label>
            Jogo
            <select value={data.isHome} onChange={event => setData({ ...data, isHome: event.target.value })}>
              <option value="true">Em casa</option>
              <option value="false">Fora</option>
            </select>
          </label>
        </div>
        <ModalActions saving={saving} onClose={onClose} />
      </form>
    </Modal>
  );
}
