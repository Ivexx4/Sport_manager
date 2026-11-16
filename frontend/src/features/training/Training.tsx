import { useEffect, useState } from "react";
import { CalendarDays, Plus } from "lucide-react";
import { api, type Team, type TrainingSession } from "../../api";
import { Empty, Modal, ModalActions, PageHeader, useToast } from "../../components";
import { useSubmit } from "../../hooks/useSubmit";
import { formatDateTime, formatTime } from "../../lib/format";

/** Calendário de treinos do clube. */
export function Training({ canManage }: { canManage: boolean }) {
  const toast = useToast();
  const [sessions, setSessions] = useState<TrainingSession[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [modal, setModal] = useState(false);

  async function load() {
    try {
      const [items, clubTeams] = await Promise.all([api.trainingSessions(), api.teams()]);
      setSessions(items);
      setTeams(clubTeams);
    } catch {
      toast.error("Não foi possível carregar o calendário de treinos.");
    }
  }

  useEffect(() => {
    load();
  }, []);

  return (
    <section>
      <PageHeader
        eyebrow="CALENDÁRIO"
        title="Treinos"
        description="Planeia sessões, espaços e presenças."
        action={
          canManage ? (
            <button className="button button-primary" onClick={() => setModal(true)}>
              <Plus size={16} /> Novo treino
            </button>
          ) : undefined
        }
      />
      <div className="panel table-panel">
        <table>
          <thead>
            <tr>
              <th>Data e hora</th>
              <th>Equipa</th>
              <th>Local</th>
              <th>Estado</th>
            </tr>
          </thead>
          <tbody>
            {[...sessions]
              .sort((first, second) => first.startTime.localeCompare(second.startTime))
              .map(session => (
                <tr key={session.id}>
                  <td>
                    <strong>{formatDateTime(session.startTime)}</strong>
                    <small className="table-subtext">
                      {formatTime(session.startTime)} – {formatTime(session.endTime)}
                    </small>
                  </td>
                  <td>{teams.find(team => team.id === session.teamId)?.name || "Equipa"}</td>
                  <td>{session.courtId ? "Espaço reservado" : "Por definir"}</td>
                  <td>
                    <span className="tag tag-green">{session.status === "CANCELLED" ? "Cancelado" : "Agendado"}</span>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
        {sessions.length === 0 && (
          <Empty
            icon={<CalendarDays />}
            title="Calendário vazio"
            text="Cria o primeiro treino da época para começar a controlar presenças."
          />
        )}
      </div>
      {modal && (
        <TrainingModal
          teams={teams}
          onClose={() => setModal(false)}
          onSaved={() => {
            setModal(false);
            load();
            toast.success("Treino agendado com sucesso.");
          }}
        />
      )}
    </section>
  );
}

function TrainingModal({ teams, onClose, onSaved }: { teams: Team[]; onClose: () => void; onSaved: () => void }) {
  const { saving, submit: run } = useSubmit();
  const [data, setData] = useState({
    teamId: teams[0]?.id ?? "",
    startTime: "",
    endTime: "",
    recurrence: "",
    notes: ""
  });

  function submit(event: React.FormEvent) {
    event.preventDefault();
    void run(
      () =>
        api.createTrainingSession({
          ...data,
          startTime: new Date(data.startTime).toISOString(),
          endTime: new Date(data.endTime).toISOString(),
          status: "ACTIVE"
        }),
      { onSuccess: onSaved, errorMessage: "Não foi possível agendar o treino." }
    );
  }

  return (
    <Modal title="Novo treino" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
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
        <div className="form-grid">
          <label>
            Início
            <input
              required
              type="datetime-local"
              value={data.startTime}
              onChange={event => setData({ ...data, startTime: event.target.value })}
            />
          </label>
          <label>
            Fim
            <input
              required
              type="datetime-local"
              value={data.endTime}
              onChange={event => setData({ ...data, endTime: event.target.value })}
            />
          </label>
        </div>
        <label>
          Repetição
          <select value={data.recurrence} onChange={event => setData({ ...data, recurrence: event.target.value })}>
            <option value="">Não repetir</option>
            <option value="WEEKLY">Semanal</option>
            <option value="BIWEEKLY">Quinzenal</option>
          </select>
          <small className="field-help">A recorrência fica associada à marcação para o calendário do clube.</small>
        </label>
        <label>
          Notas
          <input
            placeholder="Pavilhão, foco da sessão..."
            value={data.notes}
            onChange={event => setData({ ...data, notes: event.target.value })}
          />
        </label>
        <ModalActions saving={saving} onClose={onClose} />
      </form>
    </Modal>
  );
}
