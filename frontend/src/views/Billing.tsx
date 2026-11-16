import { useEffect, useState } from "react";
import { Activity, BookOpen, CreditCard, Plus, ShieldCheck } from "lucide-react";
import { api, type Athlete, type Charge, type FeePlan, type LedgerEntry } from "../api";
import { Empty, Modal, ModalActions, PageHeader, Stat } from "../components/ui";
import { formatDate, formatMoney } from "../format";
import type { ReportError, ShowToast } from "../types";

/** Quotas, pagamentos e movimentos do clube. */
export function Billing({ showToast, canManage }: { showToast: ShowToast; canManage: boolean }) {
  const [plans, setPlans] = useState<FeePlan[]>([]);
  const [athletes, setAthletes] = useState<Athlete[]>([]);
  const [charges, setCharges] = useState<Charge[]>([]);
  const [ledger, setLedger] = useState<LedgerEntry[]>([]);
  const [modal, setModal] = useState<"plan" | "charge" | "generate" | "ledger" | null>(null);
  const [paymentCharge, setPaymentCharge] = useState<Charge | null>(null);

  async function load() {
    try {
      const currentCharges = await api.charges();
      if (canManage) {
        const [feePlans, entries, athletePage] = await Promise.all([
          api.feePlans(),
          api.ledgerEntries(),
          api.athletes({ status: "ACTIVE", size: 100 })
        ]);
        setPlans(feePlans);
        setLedger(entries);
        setAthletes(athletePage.items);
      } else {
        setPlans([]);
        setLedger([]);
        setAthletes([]);
      }
      setCharges(currentCharges);
    } catch {
      showToast({ kind: "error", message: "Não foi possível carregar o financeiro." });
    }
  }

  useEffect(() => {
    load();
  }, [canManage]);

  const pending = charges.filter(charge => charge.status === "PENDING" || charge.status === "OVERDUE");
  const received = charges
    .filter(charge => charge.status === "PAID")
    .reduce((total, charge) => total + charge.amountCents, 0);
  const income = ledger.filter(entry => entry.type === "INCOME").reduce((total, entry) => total + entry.amountCents, 0);
  const expenses = ledger
    .filter(entry => entry.type === "EXPENSE")
    .reduce((total, entry) => total + entry.amountCents, 0);

  async function markPaid(charge: Charge) {
    try {
      await api.updateChargeStatus(charge.id, "PAID");
      await load();
      showToast({ kind: "success", message: "Cobrança marcada como paga." });
    } catch {
      showToast({ kind: "error", message: "Não foi possível atualizar a cobrança." });
    }
  }

  function chargeStatusLabel(status: string) {
    if (status === "PAID") return "Paga";
    if (status === "OVERDUE") return "Em atraso";
    if (status === "CANCELLED") return "Cancelada";
    return "Pendente";
  }

  return (
    <section>
      <PageHeader
        eyebrow="FINANCEIRO"
        title="Quotas e financeiro"
        description="Quotas recorrentes, taxas únicas, pagamentos e movimentos do clube."
        action={
          canManage ? (
            <div className="heading-actions">
              <button className="button button-secondary" onClick={() => setModal("ledger")}>
                <Plus size={16} /> Lançamento
              </button>
              <button className="button button-secondary" onClick={() => setModal("charge")}>
                <Plus size={16} /> Nova taxa
              </button>
              <button className="button button-secondary" onClick={() => setModal("generate")}>
                <Activity size={16} /> Gerar quotas
              </button>
              <button className="button button-primary" onClick={() => setModal("plan")}>
                <Plus size={16} /> Novo plano
              </button>
            </div>
          ) : undefined
        }
      />
      <div className="stat-grid billing-stats">
        <Stat icon={<CreditCard />} label="Planos ativos" value={plans.length} color="blue" />
        <Stat icon={<Activity />} label="Cobranças pendentes" value={pending.length} color="orange" />
        <Stat icon={<ShieldCheck />} label="Recebido" value={formatMoney(received)} color="green" text />
        <Stat
          icon={<BookOpen />}
          label="Saldo contabilístico"
          value={formatMoney(income - expenses)}
          color="purple"
          text
        />
      </div>
      <div className="two-col">
        <div className="panel table-panel">
          <div className="panel-heading">
            <h3>Cobranças recentes</h3>
            <span className="muted">{charges.length} registos</span>
          </div>
          <table>
            <thead>
              <tr>
                <th>Período</th>
                <th>Vencimento</th>
                <th>Valor</th>
                <th>Estado</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {charges.slice(0, 12).map(charge => (
                <tr key={charge.id}>
                  <td>{charge.billingPeriod || "Manual"}</td>
                  <td>{formatDate(charge.dueDate)}</td>
                  <td>
                    <strong>{formatMoney(charge.amountCents)}</strong>
                  </td>
                  <td>
                    <span className={`tag ${charge.status === "PAID" ? "tag-green" : "tag-gray"}`}>
                      {chargeStatusLabel(charge.status)}
                    </span>
                  </td>
                  <td>
                    {canManage && charge.status !== "PAID" && charge.status !== "CANCELLED" && (
                      <div className="heading-actions">
                        <button className="button button-light" onClick={() => setPaymentCharge(charge)}>
                          Registar pagamento
                        </button>
                        <button className="icon-button" title="Marcar como paga" onClick={() => markPaid(charge)}>
                          ✓
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {charges.length === 0 && (
            <Empty
              icon={<CreditCard />}
              title="Ainda não há cobranças"
              text="Cria um plano e gera cobranças para os atletas elegíveis."
            />
          )}
        </div>
        <div className="panel table-panel">
          <div className="panel-heading">
            <h3>Movimentos</h3>
            <span className="muted">{ledger.length} registos</span>
          </div>
          <table>
            <thead>
              <tr>
                <th>Data</th>
                <th>Categoria</th>
                <th>Valor</th>
              </tr>
            </thead>
            <tbody>
              {ledger.slice(0, 10).map(entry => (
                <tr key={entry.id}>
                  <td>{formatDate(entry.entryDate)}</td>
                  <td>
                    {entry.category}
                    <small className="table-subtext">
                      {entry.description || (entry.type === "INCOME" ? "Receita" : "Despesa")}
                    </small>
                  </td>
                  <td className={entry.type === "INCOME" ? "amount-in" : "amount-out"}>
                    <strong>
                      {entry.type === "INCOME" ? "+" : "-"}
                      {formatMoney(entry.amountCents)}
                    </strong>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {ledger.length === 0 && (
            <Empty
              icon={<BookOpen />}
              title="Sem movimentos"
              text="Regista receitas e despesas para acompanhar o saldo."
            />
          )}
        </div>
      </div>
      {modal === "plan" && (
        <FeePlanModal
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            load();
            showToast({ kind: "success", message: "Plano de quotas criado." });
          }}
          onError={message => showToast({ kind: "error", message })}
        />
      )}
      {modal === "charge" && (
        <ChargeModal
          athletes={athletes}
          plans={plans.filter(plan => plan.periodicity === "ONE_TIME")}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            load();
            showToast({ kind: "success", message: "Taxa registada." });
          }}
          onError={message => showToast({ kind: "error", message })}
        />
      )}
      {modal === "generate" && (
        <GenerateChargesModal
          plans={plans}
          onClose={() => setModal(null)}
          onSaved={result => {
            setModal(null);
            load();
            showToast({
              kind: "success",
              message: `${result.created} cobranças criadas (${result.skipped} já existentes).`
            });
          }}
          onError={message => showToast({ kind: "error", message })}
        />
      )}
      {modal === "ledger" && (
        <LedgerModal
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            load();
            showToast({ kind: "success", message: "Lançamento registado." });
          }}
          onError={message => showToast({ kind: "error", message })}
        />
      )}
      {paymentCharge && (
        <PaymentModal
          charge={paymentCharge}
          onClose={() => setPaymentCharge(null)}
          onSaved={() => {
            setPaymentCharge(null);
            load();
            showToast({ kind: "success", message: "Pagamento registado." });
          }}
          onError={message => showToast({ kind: "error", message })}
        />
      )}
    </section>
  );
}

function FeePlanModal({
  onClose,
  onSaved,
  onError
}: {
  onClose: () => void;
  onSaved: () => void;
  onError: ReportError;
}) {
  const [data, setData] = useState({ name: "", amount: "", periodicity: "MONTHLY" });
  const [saving, setSaving] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      await api.createFeePlan({
        name: data.name,
        amountCents: Math.round(Number(data.amount) * 100),
        periodicity: data.periodicity
      });
      onSaved();
    } catch {
      onError("Não foi possível criar o plano de quotas.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Novo plano ou taxa" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <label>
          Nome
          <input
            required
            placeholder="Mensalidade Sub-16 ou taxa de inscrição"
            value={data.name}
            onChange={event => setData({ ...data, name: event.target.value })}
          />
        </label>
        <div className="form-grid">
          <label>
            Valor (€)
            <input
              required
              min="0"
              step="0.01"
              type="number"
              value={data.amount}
              onChange={event => setData({ ...data, amount: event.target.value })}
            />
          </label>
          <label>
            Tipo
            <select value={data.periodicity} onChange={event => setData({ ...data, periodicity: event.target.value })}>
              <option value="MONTHLY">Quota mensal</option>
              <option value="QUARTERLY">Quota trimestral</option>
              <option value="YEARLY">Quota anual</option>
              <option value="ONE_TIME">Taxa única</option>
            </select>
          </label>
        </div>
        <small className="field-help">
          {data.periodicity === "ONE_TIME"
            ? "A taxa é cobrada manualmente uma vez por atleta através de Nova taxa."
            : "As quotas podem ser geradas automaticamente para os atletas elegíveis pela época e escalão."}
        </small>
        <ModalActions saving={saving} onClose={onClose} />
      </form>
    </Modal>
  );
}

function ChargeModal({
  athletes,
  plans,
  onClose,
  onSaved,
  onError
}: {
  athletes: Athlete[];
  plans: FeePlan[];
  onClose: () => void;
  onSaved: () => void;
  onError: ReportError;
}) {
  const [data, setData] = useState({
    athleteId: athletes[0]?.id ?? "",
    feePlanId: plans[0]?.id ?? "",
    dueDate: new Date().toISOString().slice(0, 10)
  });
  const [saving, setSaving] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const plan = plans.find(item => item.id === data.feePlanId);
    if (!plan) {
      onError("Cria primeiro um plano do tipo taxa única.");
      return;
    }
    setSaving(true);
    try {
      await api.createCharge({
        athleteId: data.athleteId,
        feePlanId: plan.id,
        amountCents: plan.amountCents,
        dueDate: data.dueDate,
        status: "PENDING"
      });
      onSaved();
    } catch {
      onError("Não foi possível registar a taxa.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Nova taxa única" onClose={onClose}>
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
          Taxa
          <select
            required
            value={data.feePlanId}
            onChange={event => setData({ ...data, feePlanId: event.target.value })}
          >
            <option value="" disabled>
              Seleciona uma taxa única
            </option>
            {plans.map(plan => (
              <option key={plan.id} value={plan.id}>
                {plan.name} · {formatMoney(plan.amountCents)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Data de vencimento
          <input
            required
            type="date"
            value={data.dueDate}
            onChange={event => setData({ ...data, dueDate: event.target.value })}
          />
        </label>
        {athletes.length === 0 && <p className="error-text">Não existem atletas ativos para atribuir a taxa.</p>}
        {plans.length === 0 && <p className="error-text">Cria primeiro um plano com o tipo Taxa única.</p>}
        <ModalActions
          saving={saving}
          disabled={athletes.length === 0 || plans.length === 0}
          onClose={onClose}
          submitLabel="Registar taxa"
        />
      </form>
    </Modal>
  );
}

function GenerateChargesModal({
  plans,
  onClose,
  onSaved,
  onError
}: {
  plans: FeePlan[];
  onClose: () => void;
  onSaved: (result: { created: number; skipped: number }) => void;
  onError: ReportError;
}) {
  const [period, setPeriod] = useState(new Date().toISOString().slice(0, 7));
  const [feePlanId, setFeePlanId] = useState("");
  const [dryRun, setDryRun] = useState(false);
  const [saving, setSaving] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      const result = await api.generateCharges({ period, feePlanId: feePlanId || undefined, dryRun });
      if (dryRun) {
        onError(`Simulação: seriam criadas ${result.created} cobranças e ignoradas ${result.skipped}.`);
      } else onSaved(result);
    } catch {
      onError("Não foi possível gerar as cobranças.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Gerar cobranças" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <label>
          Período
          <input required type="month" value={period} onChange={event => setPeriod(event.target.value)} />
          <small className="field-help">Use o mês de referência no formato AAAA-MM.</small>
        </label>
        <label>
          Plano
          <select value={feePlanId} onChange={event => setFeePlanId(event.target.value)}>
            <option value="">Todos os planos</option>
            {plans.map(plan => (
              <option key={plan.id} value={plan.id}>
                {plan.name} · {formatMoney(plan.amountCents)}
              </option>
            ))}
          </select>
        </label>
        <label className="check-option">
          <input type="checkbox" checked={dryRun} onChange={event => setDryRun(event.target.checked)} /> Simular sem
          gravar cobranças
        </label>
        <ModalActions saving={saving} onClose={onClose} submitLabel={dryRun ? "Simular" : "Gerar"} />
      </form>
    </Modal>
  );
}

function PaymentModal({
  charge,
  onClose,
  onSaved,
  onError
}: {
  charge: Charge;
  onClose: () => void;
  onSaved: () => void;
  onError: ReportError;
}) {
  const [data, setData] = useState({ method: "CASH", amount: (charge.amountCents / 100).toFixed(2), reference: "" });
  const [saving, setSaving] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      await api.createPayment({
        chargeId: charge.id,
        method: data.method,
        amountCents: Math.round(Number(data.amount) * 100),
        reference: data.reference || undefined
      });
      const payments = await api.payments(charge.id);
      const received = payments.reduce((total, payment) => total + payment.amountCents, 0);
      if (received >= charge.amountCents) await api.updateChargeStatus(charge.id, "PAID");
      onSaved();
    } catch {
      onError("Não foi possível registar o pagamento.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Registar pagamento" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <p className="muted">
          Cobrança de {formatMoney(charge.amountCents)} · {charge.billingPeriod || "manual"}
        </p>
        <div className="form-grid">
          <label>
            Valor (€)
            <input
              required
              min="0.01"
              step="0.01"
              type="number"
              value={data.amount}
              onChange={event => setData({ ...data, amount: event.target.value })}
            />
          </label>
          <label>
            Método
            <select value={data.method} onChange={event => setData({ ...data, method: event.target.value })}>
              <option value="CASH">Numerário</option>
              <option value="MULTIBANCO">Multibanco</option>
              <option value="MBWAY">MB WAY</option>
              <option value="TRANSFER">Transferência</option>
              <option value="OTHER">Outro</option>
            </select>
          </label>
        </div>
        <label>
          Referência
          <input
            placeholder="Opcional"
            value={data.reference}
            onChange={event => setData({ ...data, reference: event.target.value })}
          />
        </label>
        <ModalActions saving={saving} onClose={onClose} submitLabel="Registar" />
      </form>
    </Modal>
  );
}

function LedgerModal({
  onClose,
  onSaved,
  onError
}: {
  onClose: () => void;
  onSaved: () => void;
  onError: ReportError;
}) {
  const [data, setData] = useState({
    type: "EXPENSE",
    category: "",
    amount: "",
    entryDate: new Date().toISOString().slice(0, 10),
    description: ""
  });
  const [saving, setSaving] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      await api.createLedgerEntry({ ...data, amountCents: Math.round(Number(data.amount) * 100) });
      onSaved();
    } catch {
      onError("Não foi possível registar o lançamento.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Novo lançamento" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <div className="form-grid">
          <label>
            Tipo
            <select value={data.type} onChange={event => setData({ ...data, type: event.target.value })}>
              <option value="INCOME">Receita</option>
              <option value="EXPENSE">Despesa</option>
            </select>
          </label>
          <label>
            Data
            <input
              required
              type="date"
              value={data.entryDate}
              onChange={event => setData({ ...data, entryDate: event.target.value })}
            />
          </label>
        </div>
        <div className="form-grid">
          <label>
            Categoria
            <input
              required
              placeholder="Material, inscrição..."
              value={data.category}
              onChange={event => setData({ ...data, category: event.target.value })}
            />
          </label>
          <label>
            Valor (€)
            <input
              required
              min="0"
              step="0.01"
              type="number"
              value={data.amount}
              onChange={event => setData({ ...data, amount: event.target.value })}
            />
          </label>
        </div>
        <label>
          Descrição
          <input
            placeholder="Opcional"
            value={data.description}
            onChange={event => setData({ ...data, description: event.target.value })}
          />
        </label>
        <ModalActions saving={saving} onClose={onClose} />
      </form>
    </Modal>
  );
}
