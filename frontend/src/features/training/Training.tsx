import { useEffect, useState } from "react";
import { CalendarDays, Plus, UserCheck } from "lucide-react";
import { api, type AttendanceView, type Team, type TrainingSession } from "../../api";
import { Empty, Loading, Modal, ModalActions, PageHeader, useToast } from "../../components";
import { useSubmit } from "../../hooks/useSubmit";
import { formatDateTime, formatTime } from "../../lib/format";

const ATTENDANCE_STATUS: { value: string; label: string; tag: string }[] = [
  { value: "PRESENT", label: "Presente", tag: "tag-green" },
  { value: "LATE", label: "Atrasado", tag: "tag-yellow" },
  { value: "JUSTIFIED", label: "Justificada", tag: "tag-blue" },
  { value: "ABSENT", label: "Ausente", tag: "tag-red" }
];

/** Calendário de treinos do clube, com marcação de presenças por sessão. */
export function Training({ canManage }: { canManage: boolean }) {
  const toast = useToast();
  const [sessions, setSessions] = useState<TrainingSession[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [modal, setModal] = useState(false);
  const [selectedSession, setSelectedSession] = useState<TrainingSession | null>(null);
  const [attendance, setAttendance] = useState<AttendanceView[]>([]);
  const [attendanceLoading, setAttendanceLoading] = useState(false);
  const [saving, setSaving] = useState<Record<string, boolean>>({});

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

  async function loadAttendance(session: TrainingSession) {
    setSelectedSession(session);
    setAttendance([]);
    setAttendanceLoading(true);
    try {
      const items = await api.attendance(session.id);
      setAttendance(items);
    } catch {
      toast.error("Não foi possível carregar as presenças deste treino.");
    } finally {
      setAttendanceLoading(false);
    }
  }

  async function setAttendanceStatus(athleteId: string, status: string) {
    if (!selectedSession) return;
    setSaving(prev => ({ ...prev, [athleteId]: true }));
    try {
      await api.setAttendance(selectedSession.id, athleteId, status);
      loadAttendance(selectedSession);
      toast.success("Presença atualizada.");
    } catch {
      toast.error("Não foi possível atualizar a presença.");
    } finally {
      setSaving(prev => {
        const copy = { ...prev };
        delete copy[athleteId];
        return copy;
      });
    }
  }

  async function removeAttendance(athleteId: string) {
    if (!selectedSession) return;
    if (!window.confirm("Remover o registo de presença deste atleta?")) return;
    setSaving(prev => ({ ...prev, [athleteId]: true }));
    try {
      await api.removeAttendance(selectedSession.id, athleteId);
      loadAttendance(selectedSession);
      toast.success("Registo de presença removido.");
    } catch {
      toast.error("Não foi possível remover o registo.");
    } finally {
      setSaving(prev => {
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
              <th />
            </tr>
          </thead>
          <tbody>
            {[...sessions]
              .sort((first, second) => first.startTime.localeCompare(second.startTime))
              .map(session => (
                <tr
                  key={session.id}
                  className={`clickable-row ${selectedSession?.id === session.id ? "selected-row" : ""}`}
                  onClick={() => loadAttendance(session)}
                >
                  <td>
                    <strong>{formatDateTime(session.startTime)}</strong>
                    <small className="table-subtext">
                      {formatTime(session.startTime)} – {formatTime(session.endTime)}
                    </small>
                  </td>
                  <td>{teamById(session.teamId)}</td>
                  <td>{session.courtId ? "Espaço reservado" : "Por definir"}</td>
                  <td>
                    <span className="tag tag-green">{session.status === "CANCELLED" ? "Cancelado" : "Agendado"}</span>
                  </td>
                  <td>
                    <button className="icon-button ghost" title="Ver presenças">
                      <UserCheck size={17} />
                    </button>
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

      {selectedSession && (
        <div className="panel attendance-panel">
          <div className="attendance-header">
            <h3>Presenças — {formatDateTime(selectedSession.startTime)}</h3>
            <span className="toolbar-count">
              {attendance.length} registo(s) · equipa {teamById(selectedSession.teamId)}
            </span>
          </div>
          {attendanceLoading ? (
            <Loading />
          ) : attendance.length === 0 ? (
            <Empty
              icon={<UserCheck />}
              title="Sem presenças registadas"
              text="Marque a presença dos atletas desta sessão."
            />
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Atleta</th>
                  <th>Estado</th>
                  <th>Justificação</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {attendance.map(record => (
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
                      <select
                        value={ATTENDANCE_STATUS.some(s => s.value === record.status) ? record.status : "PRESENT"}
                        onChange={event => setAttendanceStatus(record.athleteId, event.target.value)}
                        disabled={saving[record.athleteId]}
                        className={`status-select ${ATTENDANCE_STATUS.find(s => s.value === record.status)?.tag}`}
                      >
                        {ATTENDANCE_STATUS.map(option => (
                          <option key={option.value} value={option.value}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td>
                      {record.justification ? (
                        <span className="table-subtext">{record.justification}</span>
                      ) : (
                        <span className="table-subtext">—</span>
                      )}
                    </td>
                    <td>
                      <button
                        className="button button-danger small"
                        onClick={() => removeAttendance(record.athleteId)}
                        disabled={saving[record.athleteId]}
                      >
                        {saving[record.athleteId] ? "A remover..." : "Remover"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

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
