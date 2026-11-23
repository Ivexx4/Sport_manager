import { useEffect, useState } from "react";
import { FileText, Plus } from "lucide-react";
import { api, type Athlete, type Document } from "../../api";
import { Empty, Modal, ModalActions, PageHeader, useToast } from "../../components";
import { useSubmit } from "../../hooks/useSubmit";
import { formatDate } from "../../lib/format";

/** Gestão de documentos do clube e dos atletas. */
export function Documents() {
  const toast = useToast();
  const [documents, setDocuments] = useState<Document[]>([]);
  const [athletes, setAthletes] = useState<Athlete[]>([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false);

  async function load() {
    setLoading(true);
    try {
      const athletePage = await api.athletes({ status: "ACTIVE", size: 100 });
      setAthletes(athletePage.items);
      const allDocs: Document[] = [];
      for (const athlete of athletePage.items) {
        try {
          const docs = await api.documents(athlete.id);
          allDocs.push(...docs);
        } catch {
          // Ignora se falhar para um atleta específico
        }
      }
      setDocuments(allDocs);
    } catch {
      toast.error("Não foi possível carregar os documentos.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  return (
    <section>
      <PageHeader
        eyebrow="SECRETARIA"
        title="Documentos"
        description="Gestão de exames médicos, termos de responsabilidade e documentação dos atletas."
        action={
          <button className="button button-primary" onClick={() => setModal(true)}>
            <Plus size={16} /> Novo documento
          </button>
        }
      />

      {loading ? (
        <div className="panel empty-panel">A carregar documentos...</div>
      ) : documents.length === 0 ? (
        <Empty icon={<FileText size={32} />} title="Sem documentos registados" text="Adiciona documentos associados aos atletas do clube." />
      ) : (
        <div className="panel table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Tipo</th>
                <th>Atleta</th>
                <th>Ficheiro</th>
                <th>Válido até</th>
                <th className="text-right">Ações</th>
              </tr>
            </thead>
            <tbody>
              {documents.map(doc => {
                const athlete = athletes.find(a => a.id === doc.athleteId);
                return (
                  <tr key={doc.id}>
                    <td>
                      <span className="badge badge-blue">{doc.type}</span>
                    </td>
                    <td><strong>{athlete ? athlete.fullName : "Atleta"}</strong></td>
                    <td>{doc.fileName || "Ficheiro sem nome"}</td>
                    <td>{formatDate(doc.validUntil)}</td>
                    <td className="text-right">
                      {doc.fileUrl && (
                        <a href={doc.fileUrl} target="_blank" rel="noreferrer" className="button button-subtle">
                          Ver
                        </a>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {modal && <NewDocumentModal athletes={athletes} onClose={() => setModal(false)} onSaved={load} />}
    </section>
  );
}

function NewDocumentModal({
  athletes,
  onClose,
  onSaved
}: {
  athletes: Athlete[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { saving, submit } = useSubmit();
  const [data, setData] = useState({
    athleteId: athletes[0]?.id ?? "",
    type: "EXAME_MEDICO",
    fileName: "",
    fileUrl: "",
    validUntil: ""
  });

  function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!data.athleteId) return;
    void submit(
      () =>
        api.createDocument(data.athleteId, {
          type: data.type,
          fileName: data.fileName || "",
          fileUrl: data.fileUrl || "",
          validUntil: data.validUntil || undefined
        }),
      {
        onSuccess: () => {
          onSaved();
          onClose();
        },
        errorMessage: "Não foi possível criar o documento."
      }
    );
  }

  return (
    <Modal title="Novo documento" onClose={onClose}>
      <form className="stack" onSubmit={onSubmit}>
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
          Tipo de documento
          <select value={data.type} onChange={event => setData({ ...data, type: event.target.value })}>
            <option value="EXAME_MEDICO">Exame Médico</option>
            <option value="TERMO_RESPONSABILIDADE">Termo de Responsabilidade</option>
            <option value="EXAME_MEDICO_DESPORTIVO">Exame Médico Desportivo</option>
            <option value="OUTRO">Outro</option>
          </select>
        </label>
        <label>
          Nome do ficheiro
          <input
            placeholder="exame_medico_2026.pdf"
            value={data.fileName}
            onChange={event => setData({ ...data, fileName: event.target.value })}
          />
        </label>
        <label>
          URL do ficheiro
          <input
            placeholder="https://..."
            value={data.fileUrl}
            onChange={event => setData({ ...data, fileUrl: event.target.value })}
          />
        </label>
        <label>
          Válido até
          <input
            type="date"
            value={data.validUntil}
            onChange={event => setData({ ...data, validUntil: event.target.value })}
          />
        </label>
        <ModalActions saving={saving} onClose={onClose} />
      </form>
    </Modal>
  );
}
