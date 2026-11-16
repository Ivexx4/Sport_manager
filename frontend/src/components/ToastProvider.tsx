import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { Toast } from "../types";

/** O que os ecrãs e modais podem pedir ao sistema de notificações. */
export interface ToastApi {
  success(message: string): void;
  error(message: string): void;
}

const ToastContext = createContext<ToastApi | null>(null);

/** Notificações do canto inferior: um só lugar para o estado, o temporizador e o desenho. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<Toast | null>(null);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 4200);
    return () => clearTimeout(timer);
  }, [toast]);

  const show = useCallback((next: Toast) => setToast(next), []);
  const value = useMemo<ToastApi>(
    () => ({
      success: message => show({ kind: "success", message }),
      error: message => show({ kind: "error", message })
    }),
    [show]
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      {toast && (
        <div className={`toast ${toast.kind}`}>
          <span>{toast.kind === "success" ? "✓" : "!"}</span>
          {toast.message}
        </div>
      )}
    </ToastContext.Provider>
  );
}

/** Acesso às notificações; tem de ser usado dentro de {@link ToastProvider}. */
export function useToast(): ToastApi {
  const toast = useContext(ToastContext);
  if (!toast) throw new Error("useToast tem de ser usado dentro de ToastProvider");
  return toast;
}
