import { useEffect, useState } from "react";
import { ChevronRight, ShieldCheck } from "lucide-react";
import { api, authStore, isMfaChallenge, type Membership, type MfaChallenge, type MfaSetup } from "../../api";

/** Entrada na aplicação: login, criação de clube, seleção de clube e o segundo passo (MFA). */
export function Login({ onLogin, message }: { onLogin: () => void; message?: string }) {
  const [mode, setMode] = useState<"login" | "create" | "clubs">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [bootstrapKey, setBootstrapKey] = useState("");
  const [memberships, setMemberships] = useState<Membership[]>([]);
  // Segundo passo do login: ou falta o código do autenticador, ou falta inscrever o MFA.
  const [challenge, setChallenge] = useState<MfaChallenge | null>(null);
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [code, setCode] = useState("");
  // Depois da inscrição os códigos de recuperação aparecem uma única vez.
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);

  useEffect(() => {
    if (mode === "create") {
      api
        .generateBootstrapKey()
        .then(result => setBootstrapKey(result.bootstrapKey))
        .catch(() => setError("O backend não conseguiu gerar o token de criação do clube."));
    } else {
      setBootstrapKey("");
    }
  }, [mode]);

  async function handlePostLoginAuth() {
    try {
      const items = await api.memberships();
      if (items.length === 1) {
        // Se só tem um clube, seleciona automaticamente e entra
        authStore.selectTenant({
          tenantId: items[0].tenantId,
          slug: items[0].slug,
          name: items[0].name,
          roles: items[0].roles
        });
        onLogin();
      } else if (items.length > 1) {
        setMemberships(items);
        setMode("clubs");
      } else {
        setError("Não tens associação ativa a nenhum clube.");
        authStore.clear();
      }
    } catch {
      setError("Não foi possível carregar as tuas associações a clubes.");
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      if (mode === "login") {
        const result = await api.login(email, password);
        if (isMfaChallenge(result)) {
          setChallenge(result);
          setCode("");
          if (result.mfaEnrollmentRequired) setSetup(await api.mfaEnrollmentSetup(result.challengeToken));
          return;
        }
        authStore.save(result);
        await handlePostLoginAuth();
      } else {
        const form = event.currentTarget as HTMLFormElement;
        const values = new FormData(form);
        const result = await api.provisionTenant(bootstrapKey, {
          name: String(values.get("clubName") ?? ""),
          slug: String(values.get("slug") ?? ""),
          adminEmail: email,
          adminName: String(values.get("adminName") ?? ""),
          adminPassword: password
        });
        setMode("login");
        setError(`Clube criado. Abre ${result.slug}.localhost:5173 e entra com ${result.adminEmail}.`);
      }
    } catch {
      setError(
        mode === "login"
          ? "Não foi possível iniciar sessão. Confirma o clube, email e password."
          : "Não foi possível criar o clube. Confirma a chave e os dados indicados."
      );
    } finally {
      setLoading(false);
    }
  }

  async function submitCode(event: React.FormEvent) {
    event.preventDefault();
    if (!challenge) return;
    setLoading(true);
    setError("");
    try {
      if (challenge.mfaRequired) {
        authStore.save(await api.mfaVerify(challenge.challengeToken, code));
        await handlePostLoginAuth();
      } else {
        const result = await api.mfaEnrollmentConfirm(challenge.challengeToken, code);
        authStore.save(result);
        setRecoveryCodes(result.recoveryCodes);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Não foi possível confirmar o código.");
    } finally {
      setLoading(false);
    }
  }

  function cancelMfa() {
    setChallenge(null);
    setSetup(null);
    setCode("");
    setRecoveryCodes(null);
    setError("");
  }

  const heading = recoveryCodes
    ? {
        eyebrow: "CÓDIGOS DE RECUPERAÇÃO",
        title: "Guarda estes códigos",
        text: "Cada código serve uma vez e é a forma de entrar sem o telemóvel."
      }
    : mode === "clubs"
      ? {
          eyebrow: "SELECIONAR CLUBE",
          title: "Escolhe o clube",
          text: "Tens acesso a múltiplos clubes. Seleciona em qual queres operar."
        }
      : challenge
        ? setup
          ? {
              eyebrow: "SEGURANÇA",
              title: "Ativa a autenticação em dois passos",
              text: "O perfil de administrador exige um segundo fator para entrar."
            }
          : {
              eyebrow: "SEGURANÇA",
              title: "Confirma que és tu",
              text: "Introduz o código da tua aplicação autenticadora."
            }
        : mode === "login"
          ? {
              eyebrow: "BEM-VINDO DE VOLTA",
              title: "Entrar na tua conta",
              text: "Acede ao espaço de gestão do teu clube."
            }
          : {
              eyebrow: "NOVO CLUBE",
              title: "Criar um clube",
              text: "O administrador da plataforma deve fornecer a chave de bootstrap."
            };

  return (
    <main className="login-page">
      <section className="login-art">
        <div className="brand brand-light">
          <span className="brand-mark">V</span>
          <span>
            Clube<span className="brand-dot">.</span>
          </span>
        </div>
        <div className="login-message">
          <p className="eyebrow">GESTÃO DESPORTIVA</p>
          <h1>
            Mais tempo para
            <br />
            <em>jogar.</em>
          </h1>
          <p>O teu clube, organizado num só lugar.</p>
        </div>
        <div className="ball-decoration">◌</div>
      </section>
      <section className="login-panel">
        <div className="login-card">
          <div className="brand">
            <span className="brand-mark">V</span>
            <span>
              Clube<span className="brand-dot">.</span>
            </span>
          </div>
          <div className="login-heading">
            <p className="eyebrow">{heading.eyebrow}</p>
            <h2>{heading.title}</h2>
            <p>{heading.text}</p>
          </div>
          {recoveryCodes ? (
            <div className="stack">
              <p className="field-help">
                Guarda-os num local seguro (gestor de passwords ou papel). Sobrevivem a qualquer clube e não voltam a
                ser mostrados.
              </p>
              <div className="recovery-codes">
                {recoveryCodes.map(recovery => (
                  <span key={recovery}>{recovery}</span>
                ))}
              </div>
              <button
                className="button button-primary button-wide"
                onClick={async () => {
                  await handlePostLoginAuth();
                }}
              >
                Já guardei os códigos — entrar
              </button>
            </div>
          ) : mode === "clubs" ? (
            <div className="stack">
              <div className="clubs-list" style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
                {memberships.map(club => (
                  <button
                    key={club.tenantId}
                    className="button button-secondary"
                    style={{ justifyContent: "space-between", textAlign: "left", padding: "14px 16px" }}
                    onClick={() => {
                      authStore.selectTenant({
                        tenantId: club.tenantId,
                        slug: club.slug,
                        name: club.name,
                        roles: club.roles
                      });
                      onLogin();
                    }}
                  >
                    <div>
                      <strong>{club.name}</strong>
                      <small style={{ display: "block", color: "var(--muted)" }}>{club.slug}.localhost</small>
                    </div>
                    <span className="tag tag-green">{club.roles.join(", ")}</span>
                  </button>
                ))}
              </div>
              {error && <p className="error-text">{error}</p>}
              <button
                type="button"
                className="login-switch"
                onClick={() => {
                  authStore.clear();
                  setMode("login");
                  setMemberships([]);
                }}
              >
                Voltar ao login
              </button>
            </div>
          ) : challenge ? (
            <form onSubmit={submitCode} className="stack">
              {setup ? (
                <>
                  <p className="field-help">
                    Abre a aplicação autenticadora (Google Authenticator, 1Password, Authy…), adiciona uma conta com
                    esta chave e introduz o código de 6 dígitos que aparecer.
                  </p>
                  <div className="mfa-secret">{setup.secret}</div>
                </>
              ) : (
                <p className="field-help">
                  Introduz o código de 6 dígitos da aplicação autenticadora. Se não tiveres o telemóvel, podes usar um
                  código de recuperação.
                </p>
              )}
              <label>
                Código
                <input
                  value={code}
                  onChange={event => setCode(event.target.value)}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={11}
                  placeholder="123456"
                  required
                  autoFocus
                />
              </label>
              {error && <p className="error-text">{error}</p>}
              <button className="button button-primary button-wide" disabled={loading}>
                {loading ? "A confirmar..." : setup ? "Ativar e entrar" : "Confirmar e entrar"}{" "}
                <ChevronRight size={17} />
              </button>
              <button type="button" className="login-switch" onClick={cancelMfa}>
                Voltar
              </button>
            </form>
          ) : (
            <>
              <form onSubmit={submit} className="stack">
                {mode === "create" && (
                  <>
                    <p className="field-help">O backend gerou um token de utilização única para esta criação.</p>
                    <label>
                      Nome do clube
                      <input name="clubName" required maxLength={120} placeholder="Clube de Voleibol" />
                    </label>
                    <label>
                      Slug do clube
                      <input
                        name="slug"
                        required
                        maxLength={63}
                        pattern="[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
                        placeholder="clube-novo"
                      />
                      <small className="field-help">Será usado no endereço clube-novo.localhost.</small>
                    </label>
                    <label>
                      Nome do administrador
                      <input name="adminName" required maxLength={120} placeholder="Nome completo" />
                    </label>
                  </>
                )}
                <label>
                  Email
                  <input
                    type="email"
                    value={email}
                    onChange={event => setEmail(event.target.value)}
                    placeholder="nome@clube.pt"
                    required
                  />
                </label>
                <label>
                  Password
                  <input
                    type="password"
                    value={password}
                    onChange={event => setPassword(event.target.value)}
                    placeholder="••••••••"
                    required
                  />
                </label>
                {(error || message) && <p className="error-text">{error || message}</p>}
                <button className="button button-primary button-wide" disabled={loading}>
                  {loading
                    ? mode === "login"
                      ? "A entrar..."
                      : "A criar..."
                    : mode === "login"
                      ? "Entrar"
                      : "Criar clube"}{" "}
                  <ChevronRight size={17} />
                </button>
              </form>
              <button
                className="login-switch"
                onClick={() => {
                  setMode(mode === "login" ? "create" : "login");
                  setError("");
                }}
              >
                {mode === "login" ? "Criar um novo clube" : "Já tens um clube? Entrar"}
              </button>
            </>
          )}
          <p className="login-note">
            <ShieldCheck size={15} /> Os teus dados estão protegidos e isolados por clube.
          </p>
        </div>
      </section>
    </main>
  );
}
