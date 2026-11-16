import { useState } from "react";
import { useToast } from "../components/ToastProvider";

/** Opções de uma gravação: o que fazer no sucesso e a mensagem a mostrar no erro. */
interface SubmitOptions<T> {
  /** Corre depois de o pedido ter corrido bem (fechar o modal, recarregar a lista, ...). */
  onSuccess?: (result: T) => void;
  /** Mensagem fixa para o utilizador; sem ela mostra-se a mensagem devolvida pelo servidor. */
  errorMessage?: string;
}

/**
 * O padrão repetido nos formulários dos modais: liga o estado "a guardar", corre o pedido e
 * transforma a falha numa notificação, sem cada modal repetir o try/catch/finally.
 */
export function useSubmit() {
  const toast = useToast();
  const [saving, setSaving] = useState(false);

  async function submit<T>(action: () => Promise<T>, options: SubmitOptions<T> = {}): Promise<void> {
    setSaving(true);
    try {
      const result = await action();
      options.onSuccess?.(result);
    } catch (error) {
      toast.error(options.errorMessage ?? (error instanceof Error ? error.message : "Não foi possível guardar."));
    } finally {
      setSaving(false);
    }
  }

  return { saving, submit };
}
