/** Rodapé padrão dos formulários: cancelar à esquerda, guardar à direita. */
export function ModalActions({
  saving,
  disabled,
  onClose,
  submitLabel = "Guardar"
}: {
  saving: boolean;
  disabled?: boolean;
  onClose: () => void;
  submitLabel?: string;
}) {
  return (
    <div className="modal-actions">
      <button type="button" className="button button-secondary" onClick={onClose}>
        Cancelar
      </button>
      <button className="button button-primary" disabled={saving || disabled}>
        {saving ? "A guardar..." : submitLabel}
      </button>
    </div>
  );
}
