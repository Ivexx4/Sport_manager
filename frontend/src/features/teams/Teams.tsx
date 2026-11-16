import { useEffect, useState } from "react";
import { Activity, ChevronRight, Plus, Users } from "lucide-react";
import { api, type AgeGroup, type Season, type Team, type TeamStaff } from "../../api";
import { Empty, Modal, ModalActions, PageHeader, useToast } from "../../components";
import { useSubmit } from "../../hooks/useSubmit";

/** Equipas do clube e a composição de cada uma (staff e plantel). */
export function Teams({ canManage }: { canManage: boolean }) {
  const toast = useToast();
  const [teams, setTeams] = useState<Team[]>([]);
  const [seasons, setSeasons] = useState<Season[]>([]);
  const [groups, setGroups] = useState<AgeGroup[]>([]);
  const [modal, setModal] = useState(false);
  const [selected, setSelected] = useState<Team | null>(null);

  async function load() {
    try {
      const [clubTeams, clubSeasons, ageGroups] = await Promise.all([api.teams(), api.seasons(), api.ageGroups()]);
      setTeams(clubTeams);
      setSeasons(clubSeasons);
      setGroups(ageGroups);
    } catch {
      toast.error("Não foi possível carregar as equipas.");
    }
  }

  useEffect(() => {
    load();
  }, []);

  return (
    <section>
      <PageHeader
        eyebrow="ORGANIZAÇÃO"
        title="Equipas"
        description="As equipas e os seus plantéis."
        action={
          canManage ? (
            <button className="button button-primary" onClick={() => setModal(true)}>
              <Plus size={16} /> Nova equipa
            </button>
          ) : undefined
        }
      />
      <div className="cards-grid">
        {teams.map(team => (
          <button className="team-card panel team-card-button" key={team.id} onClick={() => setSelected(team)}>
            <div className="team-cover">
              <span className="team-symbol">V</span>
              <span className={`tag ${team.status === "ACTIVE" ? "tag-green" : "tag-gray"}`}>
                {team.status === "ACTIVE" ? "Ativa" : "Inativa"}
              </span>
            </div>
            <div className="team-body">
              <h3>{team.name}</h3>
              <p>
                {seasons.find(season => season.id === team.seasonId)?.name || "Sem época"} <span>·</span>{" "}
                {team.ageGroupId ? "Escalão definido" : "Sem escalão"}
              </p>
              <div className="team-footer">
                <span>
                  <Users size={15} /> Ver plantel
                </span>
                <ChevronRight size={17} />
              </div>
            </div>
          </button>
        ))}
        {teams.length === 0 && (
          <div className="panel full-card">
            <Empty
              icon={<Activity />}
              title="Ainda não há equipas"
              text="Cria uma equipa para começar a organizar o plantel."
            />
          </div>
        )}
      </div>
      {modal && (
        <TeamModal
          seasons={seasons}
          groups={groups}
          onClose={() => setModal(false)}
          onSaved={() => {
            setModal(false);
            load();
            toast.success("Equipa criada com sucesso.");
          }}
        />
      )}
      {selected && (
        <TeamDetails
          team={selected}
          season={seasons.find(item => item.id === selected.seasonId)}
          onClose={() => setSelected(null)}
        />
      )}
    </section>
  );
}

function TeamDetails({ team, season, onClose }: { team: Team; season?: Season; onClose: () => void }) {
  const toast = useToast();
  const [roster, setRoster] = useState<{ athleteId: string; fullName: string; status: string }[]>([]);
  const [staff, setStaff] = useState<TeamStaff[]>([]);

  useEffect(() => {
    Promise.all([api.teamAthletes(team.id), api.teamStaff(team.id)])
      .then(([athleteList, coaches]) => {
        setRoster(athleteList);
        setStaff(coaches);
      })
      .catch(() => toast.error("Não foi possível carregar o plantel e os treinadores."));
  }, [team.id]);

  return (
    <Modal title={team.name} onClose={onClose}>
      <p className="muted">
        {season?.name || "Sem época"} · {team.status === "ACTIVE" ? "Ativa" : "Inativa"}
      </p>
      <h4>Treinadores</h4>
      <div className="detail-list">
        {staff.length === 0 ? (
          <p className="muted">Sem treinadores associados.</p>
        ) : (
          staff.map(coach => (
            <p key={coach.userId}>
              <strong>{coach.name}</strong>
              <span>{coach.role === "TREINADOR_ADJUNTO" ? "Adjunto" : "Treinador"}</span>
            </p>
          ))
        )}
      </div>
      <h4>Plantel</h4>
      <div className="detail-list">
        {roster.length === 0 ? (
          <Empty icon={<Users />} title="Plantel vazio" text="Ainda não existem atletas associados a esta equipa." />
        ) : (
          roster.map(athlete => (
            <p key={athlete.athleteId}>
              <strong>{athlete.fullName}</strong>
              <span>{athlete.status === "ACTIVE" ? "Ativo" : "Inativo"}</span>
            </p>
          ))
        )}
      </div>
      <div className="modal-actions">
        <button type="button" className="button button-secondary" onClick={onClose}>
          Fechar
        </button>
      </div>
    </Modal>
  );
}

function TeamModal({
  seasons,
  groups,
  onClose,
  onSaved
}: {
  seasons: Season[];
  groups: AgeGroup[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { saving, submit } = useSubmit();
  const [data, setData] = useState({ name: "", seasonId: seasons[0]?.id ?? "", ageGroupId: "", status: "ACTIVE" });
  const availableGroups = groups;

  function updateSeason(seasonId: string) {
    setData(previous => ({ ...previous, seasonId, ageGroupId: "" }));
  }

  function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    void submit(() => api.createTeam(data), {
      onSuccess: onSaved,
      errorMessage: "Não foi possível criar a equipa."
    });
  }

  return (
    <Modal title="Nova equipa" onClose={onClose}>
      <form className="stack" onSubmit={onSubmit}>
        <label>
          Nome da equipa
          <input
            required
            placeholder="Seniores Femininos"
            value={data.name}
            onChange={event => setData({ ...data, name: event.target.value })}
          />
        </label>
        <label>
          Época
          <select required value={data.seasonId} onChange={event => updateSeason(event.target.value)}>
            <option value="" disabled>
              Seleciona uma época
            </option>
            {seasons.map(item => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Escalão
          <select
            required
            value={data.ageGroupId}
            onChange={event => setData({ ...data, ageGroupId: event.target.value })}
            disabled={!data.seasonId || availableGroups.length === 0}
          >
            <option value="" disabled>
              {data.seasonId ? "Seleciona um escalão" : "Seleciona primeiro uma época"}
            </option>
            {availableGroups.map(group => (
              <option key={group.id} value={group.id}>
                {group.name} · {group.minAge}
                {group.maxAge === undefined ? "+" : `-${group.maxAge}`}
              </option>
            ))}
          </select>
          <small className="field-help">Catálogo oficial FPV, comum a todos os clubes.</small>
        </label>
        {seasons.length === 0 && <p className="error-text">Cria uma época antes de criares uma equipa.</p>}
        <ModalActions saving={saving} disabled={!data.seasonId || !data.ageGroupId} onClose={onClose} />
      </form>
    </Modal>
  );
}
