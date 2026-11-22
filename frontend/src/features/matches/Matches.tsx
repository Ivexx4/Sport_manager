import { useEffect, useState } from "react";
import { Calendar, Plus, Swords } from "lucide-react";
import { api, type CallUpView, type Match, type Team } from "../../api";
import { Empty, Loading, Modal, ModalActions, PageHeader, useToast } from "../../components";
import { useSubmit } from "../../hooks/useSubmit";
import { formatDate, formatTime } from "../../lib/format";

/** Jogos do clube e respetivas convocatórias por atleta. */
export function Matches({ canManage }: { canManage: boolean }) {
  const toast = useToast();
  const [matches, setMatches] = useState<Match[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [modal, setModal] = useState(false);
  const [selectedMatch, setSelectedMatch] = useState<Match | null>(null);
  const [callUps, setCallUps] = useState<CallUpView[]>([]);
  const [callUpsLoading, setCallUpsLoading] = useState(false);
  const [callUpSaving, setCallUpSaving] = useState<Record<string, boolean>>({});

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

  async function loadCallUps(match: Match) {
    setSelectedMatch(match);
    setCallUps([]);
    setCallUpsLoading(true);
    try {
      const items = await api.callUps(match.id);
      setCallUps(items);
    } catch {
      toast.error("Não foi possível carregar as convocatórias deste jogo.");
    } finally {
      setCallUpsLoading(false);
    }
  }

  async function removeCallUp(athleteId: string) {
    if (!selectedMatch) return;
    if (!window.confirm("Remover este atleta da convocatória?")) return;
    setCallUpSaving(prev => ({ ...prev, [athleteId]: true }));
    try {
      await api.removeCallUp(selectedMatch.id, athleteId);
      loadCallUps(selectedMatch);
      toast.success("Atleta removido da convocatória.");
    } catch {
      toast.error("Não foi possível remover o atleta.");
    } finally {
      setCallUpSaving(prev => {
        const copy = { ...prev };
        delete copy[athleteId];
        return copy;
      });
    }
  }

  const teamById = (id: string) => teams.find(team => team.id === id)?.name || "Equipa";

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
                {teamById(match.teamId)} · {match.location || "Local por definir"}
              </p>
            </div>
            <span className={`tag ${match.status === "PLAYED" ? "tag-gray" : "tag-green"}`}>
              {match.status === "PLAYED" ? "Terminado" : "Agendado"}
            </span>
            <button className="icon-button ghost" title="Ver convocatória" onClick={() => loadCallUps(match)}>
              <Swords size={17} />
            </button>
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

      {selectedMatch && (
        <Modal
          title={`Convocatória — ${formatDate(selectedMatch.matchDate.slice(0, 10))}`}
          onClose={() => setSelectedMatch(null)}
        >
          <div className="callup-panel">
            <div className="callup-header">
              <div className="callup-info">
                <strong>{selectedMatch.opponent}</strong>
                <span className="table-subtext">
                  {selectedMatch.competition} · {teamById(selectedMatch.teamId)}
                </span>
              </div>
              {canManage ? (
                <div className="callup-actions">
                  <button
                    className="button button-secondary"
                    onClick={() => {
                      setSelectedMatch(null);
                      setModal(true);
                    }}
                    disabled={callUpsLoading}
                  >
                    Criar novo jogo
                  </button>
                </div>
              ) : null}
            </div>

            {callUpsLoading ? (
              <Loading />
            ) : callUps.length === 0 ? (
              <Empty
                icon={<Calendar />}
                title="Sem atletas convocados"
                text="Ainda não há nenhum atleta na convocatória para este jogo."
              />
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>Atleta</th>
                    <th>Estado</th>
                    <th>Resposta</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {callUps.map(record => (
                    <tr key={record.athleteId}>
                      <td>
                        <div className="person-cell">
                          <span className="person-avatar">
                            {record.fullName
                              .split(" ")
                              .map(part => part[0])
                              .slice(0, 2)
                              .join("")}
                          </span>
                          <strong>{record.fullName}</strong>
                        </div>
                      </td>
                      <td>
                        <span
                          className={`tag ${record.response === "CONFIRMED" ? "tag-green" : record.response === "DECLINED" ? "tag-red" : "tag-gray"}`}
                        >
                          {record.response === "CONFIRMED"
                            ? "Convidado"
                            : record.response === "DECLINED"
                              ? "Não disponível"
                              : "Pendente"}
                        </span>
                      </td>
                      <td>
                        {record.response === "CONFIRMED" ? (
                          <span className="tag tag-green small">Confirmado</span>
                        ) : record.response === "DECLINED" ? (
                          <span className="tag tag-red small">Declinado</span>
                        ) : (
                          <span className="table-subtext">A aguardar</span>
                        )}
                        {record.respondedAt && (
                          <small className="table-subtext">
                            Respondeu {formatDate(record.respondedAt.slice(0, 10))}
                          </small>
                        )}
                      </td>
                      <td>
                        {canManage ? (
                          <>
                            <button
                              className="button button-danger small"
                              onClick={() => removeCallUp(record.athleteId)}
                              disabled={callUpSaving[record.athleteId]}
                            >
                              {callUpSaving[record.athleteId] ? "A remover..." : "Remover"}
                            </button>
                          </>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </Modal>
      )}

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
