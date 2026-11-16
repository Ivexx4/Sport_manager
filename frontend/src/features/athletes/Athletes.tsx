import { useEffect, useState } from "react";
import { ChevronRight, FileText, Plus, Search, Users } from "lucide-react";
import { api, type Athlete } from "../../api";
import { Empty, Loading, Modal, ModalActions, PageHeader, useToast } from "../../components";
import { useSubmit } from "../../hooks/useSubmit";
import { formatDate } from "../../lib/format";

/** Plantel do clube: pesquisa, importação CSV e ficha do atleta. */
export function Athletes() {
  const toast = useToast();
  const [athletes, setAthletes] = useState<Athlete[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState<"create" | "import" | null>(null);
  const [selected, setSelected] = useState<Athlete | null>(null);

  async function load() {
    setLoading(true);
    try {
      const page = await api.athletes({ q: query || undefined, size: 100 });
      setAthletes(page.items);
      setTotal(page.total);
    } catch {
      toast.error("Não foi possível carregar os atletas.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    const timer = setTimeout(load, 250);
    return () => clearTimeout(timer);
  }, [query]);

  return (
    <section>
      <PageHeader
        eyebrow="PLANTEL"
        title="Atletas"
        description={`${total} atletas registados no clube.`}
        action={
          <>
            <button className="button button-secondary" onClick={() => setModal("import")}>
              <FileText size={16} /> Importar CSV
            </button>
            <button className="button button-primary" onClick={() => setModal("create")}>
              <Plus size={16} /> Novo atleta
            </button>
          </>
        }
      />
      <div className="toolbar">
        <div className="search-box">
          <Search size={17} />
          <input placeholder="Pesquisar por nome..." value={query} onChange={event => setQuery(event.target.value)} />
        </div>
        <span className="toolbar-count">{total} resultados</span>
      </div>
      <div className="panel table-panel">
        {loading ? (
          <Loading />
        ) : athletes.length === 0 ? (
          <Empty
            icon={<Users />}
            title="Ainda não há atletas"
            text="Adiciona o primeiro atleta ou importa o plantel em CSV."
          />
        ) : (
          <table>
            <thead>
              <tr>
                <th>Atleta</th>
                <th>Data de nascimento</th>
                <th>Licença</th>
                <th>Estado</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {athletes.map(athlete => (
                <tr key={athlete.id} className="clickable-row" onClick={() => setSelected(athlete)}>
                  <td>
                    <div className="person-cell">
                      <span className="person-avatar">
                        {athlete.fullName
                          .split(" ")
                          .map(part => part[0])
                          .slice(0, 2)
                          .join("")}
                      </span>
                      <span>
                        <strong>{athlete.fullName}</strong>
                        <small>{athlete.email || "Sem email"}</small>
                      </span>
                    </div>
                  </td>
                  <td>{formatDate(athlete.birthDate)}</td>
                  <td>{athlete.licenseNumber || "—"}</td>
                  <td>
                    <span className={`tag ${athlete.status === "ACTIVE" ? "tag-green" : "tag-gray"}`}>
                      {athlete.status === "ACTIVE" ? "Ativo" : "Inativo"}
                    </span>
                  </td>
                  <td>
                    <ChevronRight size={17} className="row-arrow" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {modal === "create" && (
        <AthleteModal
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            load();
            toast.success("Atleta criado com sucesso.");
          }}
        />
      )}
      {modal === "import" && (
        <ImportModal
          onClose={() => setModal(null)}
          onImported={count => {
            setModal(null);
            load();
            toast.success(`${count} atletas importados com sucesso.`);
          }}
        />
      )}
      {selected && (
        <AthleteDetails
          athlete={selected}
          onClose={() => setSelected(null)}
          onDeleted={() => {
            setSelected(null);
            load();
            toast.success("Atleta removido com sucesso.");
          }}
        />
      )}
    </section>
  );
}

function AthleteDetails({
  athlete,
  onClose,
  onDeleted
}: {
  athlete: Athlete;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const { saving: deleting, submit } = useSubmit();

  function remove() {
    if (!window.confirm(`Remover ${athlete.fullName}? Esta ação anonimiza os dados do atleta.`)) return;
    void submit(() => api.deleteAthlete(athlete.id), {
      onSuccess: onDeleted,
      errorMessage: "Não foi possível remover o atleta."
    });
  }

  return (
    <Modal title={athlete.fullName} onClose={onClose}>
      <div className="detail-list">
        <p>
          <strong>Email</strong>
          <span>{athlete.email || "—"}</span>
        </p>
        <p>
          <strong>Data de nascimento</strong>
          <span>{formatDate(athlete.birthDate)}</span>
        </p>
        <p>
          <strong>Licença</strong>
          <span>{athlete.licenseNumber || "—"}</span>
        </p>
        <p>
          <strong>Estado</strong>
          <span>{athlete.status === "ACTIVE" ? "Ativo" : "Inativo"}</span>
        </p>
      </div>
      <div className="modal-actions">
        <button type="button" className="button button-secondary" onClick={onClose}>
          Fechar
        </button>
        <button type="button" className="button button-danger" disabled={deleting} onClick={remove}>
          {deleting ? "A remover..." : "Remover atleta"}
        </button>
      </div>
    </Modal>
  );
}

function AthleteModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const { saving, submit } = useSubmit();
  const [data, setData] = useState({
    fullName: "",
    birthDate: "",
    gender: "F",
    email: "",
    phone: "",
    licenseNumber: ""
  });
  const update = (key: string, value: string) => setData(previous => ({ ...previous, [key]: value }));

  function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    void submit(() => api.createAthlete({ ...data, status: "ACTIVE" }), {
      onSuccess: onSaved,
      errorMessage: "Não foi possível criar o atleta."
    });
  }

  return (
    <Modal title="Novo atleta" onClose={onClose}>
      <form className="stack" onSubmit={onSubmit}>
        <div className="form-grid">
          <label>
            Nome completo
            <input required value={data.fullName} onChange={event => update("fullName", event.target.value)} />
          </label>
          <label>
            Data de nascimento
            <input
              required
              type="date"
              value={data.birthDate}
              onChange={event => update("birthDate", event.target.value)}
            />
          </label>
          <label>
            Género
            <select value={data.gender} onChange={event => update("gender", event.target.value)}>
              <option value="F">Feminino</option>
              <option value="M">Masculino</option>
            </select>
          </label>
          <label>
            Nº de licença
            <input value={data.licenseNumber} onChange={event => update("licenseNumber", event.target.value)} />
          </label>
          <label>
            Email
            <input type="email" value={data.email} onChange={event => update("email", event.target.value)} />
          </label>
          <label>
            Telefone
            <input value={data.phone} onChange={event => update("phone", event.target.value)} />
          </label>
        </div>
        <ModalActions saving={saving} onClose={onClose} />
      </form>
    </Modal>
  );
}

function ImportModal({ onClose, onImported }: { onClose: () => void; onImported: (count: number) => void }) {
  const { saving, submit } = useSubmit();
  const [file, setFile] = useState<File | null>(null);

  function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!file) return;
    void submit(() => api.importAthletes(file), {
      onSuccess: result => onImported(result.imported),
      errorMessage: "Não foi possível importar o ficheiro CSV."
    });
  }

  return (
    <Modal title="Importar atletas" onClose={onClose}>
      <form className="stack" onSubmit={onSubmit}>
        <div className="upload-box">
          <FileText size={28} />
          <strong>{file ? file.name : "Escolhe um ficheiro CSV"}</strong>
          <small>Formato UTF-8 · primeira linha pode conter cabeçalho</small>
          <input type="file" accept=".csv,text/csv" onChange={event => setFile(event.target.files?.[0] ?? null)} />
        </div>
        <ModalActions saving={saving} disabled={!file} onClose={onClose} submitLabel="Importar" />
      </form>
    </Modal>
  );
}
