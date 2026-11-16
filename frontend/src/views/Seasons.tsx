import { useEffect, useState } from "react";
import { BookOpen, ChevronRight, Plus, ShieldCheck, Users } from "lucide-react";
import { api, type AgeGroup, type Athlete, type Promotion, type Season } from "../api";
import { Empty, Modal, ModalActions, PageHeader } from "../components/ui";
import { formatDate } from "../format";
import type { ReportError, ShowToast } from "../types";

/** Épocas, catálogo de escalões e autorizações de subida de escalão. */
export function Seasons({ showToast, canManage }: { showToast: ShowToast; canManage: boolean }) {
  const [seasons, setSeasons] = useState<Season[]>([]);
  const [groups, setGroups] = useState<AgeGroup[]>([]);
  const [selectedSeasonId, setSelectedSeasonId] = useState("");
  const [modal, setModal] = useState<"season" | "promotion" | null>(null);
  const [promotions, setPromotions] = useState<Promotion[]>([]);
  const [athletes, setAthletes] = useState<Athlete[]>([]);

  async function load() {
    try {
      const [seasonList, ageGroups] = await Promise.all([api.seasons(), api.ageGroups()]);
      setSeasons(seasonList);
      setGroups(ageGroups);
    } catch {
      showToast({ kind: "error", message: "Não foi possível carregar as épocas." });
    }
  }

  // As autorizações de escalão só existem para quem as pode gerir; os atletas alimentam o formulário.
  async function loadPromotions() {
    if (!canManage || !selectedSeasonId) {
      setPromotions([]);
      return;
    }
    try {
      const [items, page] = await Promise.all([
        api.promotions(selectedSeasonId),
        api.athletes({ status: "ACTIVE", size: 100 })
      ]);
      setPromotions(items);
      setAthletes(page.items);
    } catch {
      showToast({ kind: "error", message: "Não foi possível carregar as autorizações de escalão." });
    }
  }

  useEffect(() => {
    load();
  }, []);

  useEffect(() => {
    if (!selectedSeasonId && seasons[0]) setSelectedSeasonId(seasons[0].id);
  }, [seasons, selectedSeasonId]);

  useEffect(() => {
    loadPromotions();
  }, [canManage, selectedSeasonId]);

  async function togglePromotion(promotion: Promotion) {
    const status = promotion.status === "ACTIVE" ? "REVOKED" : "ACTIVE";
    try {
      await api.setPromotionStatus(selectedSeasonId, promotion.id, status);
      await loadPromotions();
      showToast({ kind: "success", message: status === "ACTIVE" ? "Autorização reativada." : "Autorização revogada." });
    } catch (error) {
      showToast({
        kind: "error",
        message: error instanceof Error ? error.message : "Não foi possível alterar a autorização."
      });
    }
  }

  const selectedSeason = seasons.find(season => season.id === selectedSeasonId);

  return (
    <section>
      <PageHeader
        eyebrow="CONFIGURAÇÃO"
        title="Épocas e escalões"
        description="Épocas do clube e catálogo oficial FPV."
        action={
          canManage ? (
            <>
              <button className="button button-secondary" onClick={() => setModal("promotion")}>
                <ShieldCheck size={16} /> Nova autorização
              </button>
              <button className="button button-primary" onClick={() => setModal("season")}>
                <Plus size={16} /> Nova época
              </button>
            </>
          ) : undefined
        }
      />
      <div className="season-selector">
        <label>
          Época
          <select value={selectedSeasonId} onChange={event => setSelectedSeasonId(event.target.value)}>
            {seasons.map(season => (
              <option key={season.id} value={season.id}>
                {season.name}
                {season.isActive ? " · ativa" : ""}
              </option>
            ))}
          </select>
        </label>
      </div>
      {seasons.length > 0 && (
        <div className="panel season-summary">
          <div className="season-icon">
            <BookOpen size={21} />
          </div>
          <div className="season-info">
            <h3>{selectedSeason?.name}</h3>
            <p>
              {selectedSeason?.startDate ? formatDate(selectedSeason.startDate) : "Sem início"} · {groups.length}{" "}
              escalões
            </p>
          </div>
        </div>
      )}
      <div className="group-list">
        {groups.map(group => (
          <div className="panel group-card" key={group.id}>
            <div className="season-icon">
              <Users size={19} />
            </div>
            <div className="season-info">
              <h3>{group.name}</h3>
              <p>
                Idades {group.minAge}
                {group.maxAge === undefined ? "+" : `-${group.maxAge}`}
              </p>
            </div>
            <ChevronRight size={19} className="row-arrow" />
          </div>
        ))}
        {seasons.length === 0 && (
          <div className="panel full-card">
            <Empty
              icon={<BookOpen />}
              title="Ainda não há épocas"
              text="Define a primeira época desportiva do clube."
            />
          </div>
        )}
      </div>
      {canManage && (
        <div className="panel table-panel">
          <div className="panel-heading">
            <h3>Autorizações de subida de escalão</h3>
            <span className="muted">{promotions.length} registos</span>
          </div>
          {promotions.length === 0 ? (
            <Empty
              icon={<ShieldCheck />}
              title="Sem autorizações nesta época"
              text="Concede uma autorização quando um atleta mais novo tem de competir num escalão acima do que a idade permite."
            />
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Atleta</th>
                  <th>Escalão</th>
                  <th>Estado</th>
                  <th>Motivo</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {promotions.map(promotion => (
                  <tr key={promotion.id}>
                    <td>
                      <div className="person-cell">
                        <strong>{promotion.athleteName}</strong>
                        <small className="table-subtext">{promotion.ageGroupCode}</small>
                      </div>
                    </td>
                    <td>{promotion.ageGroupName}</td>
                    <td>
                      <span className={`tag ${promotion.status === "ACTIVE" ? "tag-green" : "tag-gray"}`}>
                        {promotion.status === "ACTIVE" ? "Ativa" : "Revogada"}
                      </span>
                    </td>
                    <td>{promotion.reason || "—"}</td>
                    <td>
                      <button className="button button-light" onClick={() => togglePromotion(promotion)}>
                        {promotion.status === "ACTIVE" ? "Revogar" : "Reativar"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
      {modal === "season" && (
        <SeasonModal
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            load();
            showToast({ kind: "success", message: "Época criada com sucesso." });
          }}
          onError={message => showToast({ kind: "error", message })}
        />
      )}
      {modal === "promotion" && (
        <PromotionModal
          seasonId={selectedSeasonId}
          athletes={athletes}
          groups={groups}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            loadPromotions();
            showToast({ kind: "success", message: "Autorização concedida." });
          }}
          onError={message => showToast({ kind: "error", message })}
        />
      )}
    </section>
  );
}

function SeasonModal({
  onClose,
  onSaved,
  onError
}: {
  onClose: () => void;
  onSaved: () => void;
  onError: ReportError;
}) {
  const [data, setData] = useState({
    name: "",
    startDate: "",
    endDate: "",
    cutoffDate: `${new Date().getFullYear() + 1}-01-01`
  });
  const [saving, setSaving] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      await api.createSeason({ ...data, isActive: true });
      onSaved();
    } catch {
      onError("Não foi possível criar a época.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Nova época" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <label>
          Nome da época
          <input
            required
            placeholder="2026/27"
            value={data.name}
            onChange={event => setData({ ...data, name: event.target.value })}
          />
        </label>
        <div className="form-grid">
          <label>
            Data de início
            <input
              type="date"
              value={data.startDate}
              onChange={event => setData({ ...data, startDate: event.target.value })}
            />
          </label>
          <label>
            Data de fim
            <input
              type="date"
              value={data.endDate}
              onChange={event => setData({ ...data, endDate: event.target.value })}
            />
          </label>
        </div>
        <label>
          Data de referência da idade
          <input
            required
            type="date"
            value={data.cutoffDate}
            onChange={event => setData({ ...data, cutoffDate: event.target.value })}
          />
          <small className="field-help">Por padrão, 1 de janeiro do próximo ano.</small>
        </label>
        <ModalActions saving={saving} onClose={onClose} />
      </form>
    </Modal>
  );
}

function PromotionModal({
  seasonId,
  athletes,
  groups,
  onClose,
  onSaved,
  onError
}: {
  seasonId: string;
  athletes: Athlete[];
  groups: AgeGroup[];
  onClose: () => void;
  onSaved: () => void;
  onError: ReportError;
}) {
  const [data, setData] = useState({ athleteId: athletes[0]?.id ?? "", ageGroupId: "", reason: "" });
  const [saving, setSaving] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      await api.grantPromotion(seasonId, {
        athleteId: data.athleteId,
        ageGroupId: data.ageGroupId,
        reason: data.reason.trim() || undefined
      });
      onSaved();
    } catch (error) {
      onError(error instanceof Error ? error.message : "Não foi possível conceder a autorização.");
    } finally {
      setSaving(false);
    }
  }

  const blocked = !seasonId || !data.athleteId || !data.ageGroupId;

  return (
    <Modal title="Nova autorização de escalão" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <label>
          Atleta
          <select
            required
            value={data.athleteId}
            onChange={event => setData({ ...data, athleteId: event.target.value })}
          >
            <option value="" disabled>
              Seleciona um atleta
            </option>
            {athletes.map(athlete => (
              <option key={athlete.id} value={athlete.id}>
                {athlete.fullName}
              </option>
            ))}
          </select>
        </label>
        <label>
          Escalão autorizado
          <select
            required
            value={data.ageGroupId}
            onChange={event => setData({ ...data, ageGroupId: event.target.value })}
          >
            <option value="" disabled>
              Seleciona um escalão
            </option>
            {groups.map(group => (
              <option key={group.id} value={group.id}>
                {group.name} · {group.minAge}
                {group.maxAge === undefined ? "+" : `-${group.maxAge}`}
              </option>
            ))}
          </select>
          <small className="field-help">
            Só desbloqueia atletas mais novos do que a idade do escalão; descer de escalão continua proibido.
          </small>
        </label>
        <label>
          Motivo
          <input
            maxLength={200}
            placeholder="Deliberação da direção, pedido do treinador..."
            value={data.reason}
            onChange={event => setData({ ...data, reason: event.target.value })}
          />
        </label>
        {!seasonId && <p className="error-text">Seleciona uma época antes de conceder autorizações.</p>}
        {athletes.length === 0 && <p className="error-text">Não existem atletas ativos para autorizar.</p>}
        <ModalActions saving={saving} disabled={blocked} onClose={onClose} submitLabel="Autorizar" />
      </form>
    </Modal>
  );
}
