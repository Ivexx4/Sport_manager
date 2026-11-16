/** Indicador de carregamento a ocupar o lugar da lista ou tabela. */
export function Loading() {
  return (
    <div className="empty-state">
      <div className="loader" />
      <p>A carregar...</p>
    </div>
  );
}
