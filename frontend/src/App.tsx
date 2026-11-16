import { useCallback, useEffect, useState } from "react";
import { authStore } from "./api";
import { Login } from "./auth/Login";
import { Shell } from "./layout/Shell";

/** Decide entre o ecrã de entrada e o painel e reage ao fim da sessão. */
export function App() {
  const [authenticated, setAuthenticated] = useState(Boolean(authStore.access));
  const [sessionMessage, setSessionMessage] = useState("");
  const logout = useCallback(() => {
    authStore.clear();
    setAuthenticated(false);
  }, []);

  useEffect(() => {
    const handleExpired = () => {
      setSessionMessage("A tua sessão terminou. Entra novamente para continuar.");
      setAuthenticated(false);
    };
    window.addEventListener("gestao:session-expired", handleExpired);
    return () => window.removeEventListener("gestao:session-expired", handleExpired);
  }, []);

  if (!authenticated) {
    return (
      <Login
        onLogin={() => {
          setSessionMessage("");
          setAuthenticated(true);
        }}
        message={sessionMessage}
      />
    );
  }
  return <Shell onLogout={logout} />;
}
