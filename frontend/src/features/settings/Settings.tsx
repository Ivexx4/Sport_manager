import { useState } from "react";
import { PageHeader } from "../../components";

/** Definições da aplicação e preferências do clube. */
export function Settings({ canManage }: { canManage: boolean }) {
  const [clubName, setClubName] = useState("Sport Club");
  const [saved, setSaved] = useState(false);

  function handleSave(event: React.FormEvent) {
    event.preventDefault();
    setSaved(true);
    setTimeout(() => setSaved(false), 3000);
  }

  return (
    <section>
      <PageHeader
        eyebrow="CONFIGURAÇÃO"
        title="Definições"
        description="Gestão das preferências do clube e parâmetros da aplicação."
      />

      <div className="panel stack" style={{ maxWidth: "600px" }}>
        <h3>Informações do Clube</h3>
        <p className="muted">Atualiza o nome e os detalhes identificativos do clube.</p>

        <form className="stack" onSubmit={handleSave}>
          <label>
            Nome do clube
            <input
              type="text"
              value={clubName}
              onChange={event => setClubName(event.target.value)}
              disabled={!canManage}
            />
          </label>

          <div style={{ display: "flex", alignItems: "center", gap: "1rem" }}>
            <button type="submit" className="button button-primary" disabled={!canManage}>
              Guardar alterações
            </button>
            {saved && <span className="text-green" style={{ fontSize: "0.875rem" }}>Alterações guardadas com sucesso!</span>}
          </div>

          {!canManage && (
            <small className="muted">Apenas administradores e membros da direção podem alterar as definições do clube.</small>
          )}
        </form>
      </div>
    </section>
  );
}
