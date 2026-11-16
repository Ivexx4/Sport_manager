import { useEffect, useState } from "react";
import { Plus, Swords } from "lucide-react";
import { api, type Match, type Team } from "../../api";
import { Empty, Modal, ModalActions, PageHeader, useToast } from "../../components";
import { useSubmit } from "../../hooks/useSubmit";
import { formatDate, formatTime } from "../../lib/format";

/** Jogos do clube e respetivas convocatórias. */
export function Matches({ canManage }: { canManage: boolean }) {
  const toast = useToast();
  const [matches, setMatches] = useState<Match[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [modal, setModal] = useState(false);

  async function load() {
    try {
      const [items, clubTeams] = await Promise.all([api.matches(), api.teams()]);
      setMatches(items);
      setTeams(clubTeams);
    } catch {
      toast.error("Não foi possível carregar os jogos.");
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
            toast.success("Jogo criado com sucesso.");
          }}
        />
      )}
    </section>
  );
}

function MatchModal({ teams, onClose, onSaved }: { teams: Team[]; onClose: () => void; onSaved: () => void }) {
  const { saving, submit: run } = useSubmit();
  const [data, setData] = useState({
    teamId: teams[0]?.id ?? "",
    opponent: "",
    competition: "",
    location: "",
    matchDate: "",
    isHome: "true"
  });

  function submit(event: React.FormEvent) {
    event.preventDefault();
    void run(
      () =>
        api.createMatch({
          ...data,
          matchDate: new Date(data.matchDate).toISOString(),
          isHome: data.isHome === "true",
          status: "SCHEDULED"
        }),
      { onSuccess: onSaved, errorMessage: "Não foi possível criar o jogo." }
    );
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
