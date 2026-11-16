export type Role = "ADMIN" | "DIRECAO" | "FINANCEIRO" | "TREINADOR" | "ATLETA" | "ENCARREGADO";

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

/** O login ainda não terminou: falta o código do autenticador ou a inscrição do MFA. */
export interface MfaChallenge {
  mfaRequired: boolean;
  mfaEnrollmentRequired: boolean;
  challengeToken: string;
  expiresIn: number;
}

export type LoginResult = TokenResponse | MfaChallenge;

export function isMfaChallenge(result: LoginResult): result is MfaChallenge {
  return typeof (result as MfaChallenge).challengeToken === "string";
}

export interface MfaSetup {
  secret: string;
  otpauthUri: string;
}

export interface EnrollmentResult extends TokenResponse {
  recoveryCodes: string[];
}

export interface ProvisionTenantResponse {
  tenantId: string;
  slug: string;
  name: string;
  adminUserId: string;
  adminEmail: string;
}

export interface Athlete {
  id: string;
  fullName: string;
  birthDate: string;
  gender: string;
  nif?: string;
  email?: string;
  phone?: string;
  licenseNumber?: string;
  status: "ACTIVE" | "INACTIVE";
  userId?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Season {
  id: string;
  name: string;
  startDate?: string;
  endDate?: string;
  cutoffDate?: string;
  isActive: boolean;
}

export interface AgeGroup {
  id: string;
  name: string;
  minAge: number;
  maxAge?: number;
  isActive: boolean;
}

export interface Team {
  id: string;
  name: string;
  status: "ACTIVE" | "INACTIVE";
  seasonId?: string;
  ageGroupId?: string;
}

export interface TeamStaff {
  userId: string;
  name: string;
  email: string;
  role: "TREINADOR" | "TREINADOR_ADJUNTO";
}

export interface Document {
  id: string;
  athleteId: string;
  type: string;
  fileName?: string;
  fileUrl?: string;
  validUntil?: string;
}

export interface TrainingSession {
  id: string;
  teamId: string;
  courtId?: string;
  startTime: string;
  endTime: string;
  recurrence?: string;
  notes?: string;
  status?: string;
}

export interface Match {
  id: string;
  teamId: string;
  competition?: string;
  opponent: string;
  location?: string;
  courtId?: string;
  matchDate: string;
  isHome: boolean;
  status?: string;
}

export interface FeePlan {
  id: string;
  name: string;
  seasonId?: string;
  ageGroupId?: string;
  amountCents: number;
  periodicity?: string;
}

export interface Charge {
  id: string;
  athleteId: string;
  feePlanId?: string;
  amountCents: number;
  dueDate: string;
  status: string;
  billingPeriod?: string;
}

export interface Payment {
  id: string;
  chargeId: string;
  method: string;
  amountCents: number;
  reference?: string;
  paidAt: string;
}

export interface LedgerEntry {
  id: string;
  type: "INCOME" | "EXPENSE";
  category: string;
  amountCents: number;
  entryDate: string;
  description?: string;
}

/** Autorização excepcional de subida de escalão, por época × atleta × escalão. */
export interface Promotion {
  id: string;
  athleteId: string;
  athleteName: string;
  ageGroupId: string;
  ageGroupCode: string;
  ageGroupName: string;
  status: "ACTIVE" | "REVOKED";
  reason?: string;
  authorizedBy?: string;
  createdAt: string;
  updatedAt: string;
}

const API_BASE = import.meta.env.VITE_API_URL ?? "";
const ACCESS_KEY = "gestao.accessToken";
const REFRESH_KEY = "gestao.refreshToken";

export const authStore = {
  get access() {
    return localStorage.getItem(ACCESS_KEY);
  },
  get refresh() {
    return localStorage.getItem(REFRESH_KEY);
  },
  save(tokens: TokenResponse) {
    localStorage.setItem(ACCESS_KEY, tokens.accessToken);
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken);
  },
  clear() {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  }
};

function expireSession() {
  authStore.clear();
  window.dispatchEvent(new Event("gestao:session-expired"));
}

/** Corpo de erro da API no formato RFC 9457 (application/problem+json). */
interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: Record<string, string>;
}

/**
 * Mensagem legível de uma resposta de erro: usa o `detail` (e os campos inválidos) do ProblemDetail
 * que a API devolve e, se o corpo não for JSON, mostra o texto cru.
 */
async function errorMessage(response: Response): Promise<string> {
  const fallback = `Pedido falhou (${response.status})`;
  const body = await response.text();
  if (!body) return fallback;
  let problem: Problem;
  try {
    problem = JSON.parse(body) as Problem;
  } catch {
    return body;
  }
  const summary = problem.detail ?? problem.title ?? fallback;
  const fields = Object.entries(problem.errors ?? {});
  if (fields.length === 0) return summary;
  return `${summary}: ${fields.map(([field, message]) => `${field} — ${message}`).join("; ")}`;
}

async function request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (authStore.access) headers.set("Authorization", `Bearer ${authStore.access}`);
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers });
  if (response.status === 401 && retry && authStore.refresh) {
    const refreshed = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: authStore.refresh })
    });
    if (refreshed.ok) {
      authStore.save((await refreshed.json()) as TokenResponse);
      return request(path, init, false);
    }
    expireSession();
  } else if (response.status === 401) {
    expireSession();
  }
  if (!response.ok) {
    throw new Error(await errorMessage(response));
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

/**
 * Pedidos do segundo passo do login: um 401 aqui significa "código errado", não "sessão expirada",
 * por isso não passam pelo refresh automático nem limpam o armazenamento.
 */
async function challengeRequest<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  if (response.status === 401) throw new Error("Código inválido ou expirado. Tenta novamente.");
  if (!response.ok) throw new Error(await errorMessage(response));
  return response.json() as Promise<T>;
}

export const api = {
  login: (email: string, password: string) =>
    request<LoginResult>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password })
    }),
  /** Segundo fator de uma conta com MFA ativo. */
  mfaVerify: (challengeToken: string, code: string) =>
    challengeRequest<TokenResponse>("/api/v1/auth/mfa/verify", { challengeToken, code }),
  /** Início da inscrição obrigatória, antes de haver sessão. */
  mfaEnrollmentSetup: (challengeToken: string) =>
    challengeRequest<MfaSetup>("/api/v1/auth/mfa/enrollment/setup", { challengeToken }),
  /** Confirma a inscrição e abre a sessão; os códigos de recuperação só aparecem aqui. */
  mfaEnrollmentConfirm: (challengeToken: string, code: string) =>
    challengeRequest<EnrollmentResult>("/api/v1/auth/mfa/enrollment/confirm", { challengeToken, code }),
  /** Gestão do MFA da própria conta (com sessão). */
  mfaStatus: () => request<{ enabled: boolean; pending: boolean; recoveryCodesLeft: number }>("/api/v1/auth/mfa"),
  mfaSetup: () => request<MfaSetup>("/api/v1/auth/mfa/setup", { method: "POST" }),
  mfaActivate: (code: string) =>
    request<{ recoveryCodes: string[] }>("/api/v1/auth/mfa/activate", {
      method: "POST",
      body: JSON.stringify({ code })
    }),
  mfaDisable: (password: string, code: string) =>
    request<void>("/api/v1/auth/mfa/disable", {
      method: "POST",
      body: JSON.stringify({ password, code })
    }),
  provisionTenant: (bootstrapKey: string, data: Record<string, unknown>) =>
    request<ProvisionTenantResponse>("/api/v1/platform/tenants", {
      method: "POST",
      headers: { "X-Bootstrap-Key": bootstrapKey },
      body: JSON.stringify(data)
    }),
  generateBootstrapKey: () => request<{ bootstrapKey: string }>("/api/v1/platform/bootstrap-key", { method: "POST" }),
  me: () => request<{ userId: string; tenantId: string; roles: Role[] }>("/api/v1/auth/me"),
  logout: () =>
    request<void>("/api/v1/auth/logout", {
      method: "POST",
      body: JSON.stringify({ refreshToken: authStore.refresh ?? "" })
    }),
  athletes: (params: { q?: string; status?: string; page?: number; size?: number } = {}) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => value !== undefined && query.set(key, String(value)));
    return request<Page<Athlete>>(`/api/v1/athletes?${query}`);
  },
  createAthlete: (data: Record<string, unknown>) =>
    request<Athlete>("/api/v1/athletes", { method: "POST", body: JSON.stringify(data) }),
  updateAthlete: (id: string, data: Record<string, unknown>) =>
    request<Athlete>(`/api/v1/athletes/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteAthlete: (id: string) => request<void>(`/api/v1/athletes/${id}`, { method: "DELETE" }),
  importAthletes: (file: File) => {
    const body = new FormData();
    body.append("file", file);
    return request<{ imported: number }>("/api/v1/athletes/import", { method: "POST", body });
  },
  seasons: () => request<Season[]>("/api/v1/seasons"),
  createSeason: (data: Record<string, unknown>) =>
    request<Season>("/api/v1/seasons", { method: "POST", body: JSON.stringify(data) }),
  ageGroups: () => request<AgeGroup[]>("/api/v1/age-groups"),
  promotions: (seasonId: string, athleteId?: string) =>
    request<Promotion[]>(
      `/api/v1/seasons/${seasonId}/promotions${athleteId ? `?athleteId=${encodeURIComponent(athleteId)}` : ""}`
    ),
  grantPromotion: (seasonId: string, data: { athleteId: string; ageGroupId: string; reason?: string }) =>
    request<Promotion>(`/api/v1/seasons/${seasonId}/promotions`, { method: "POST", body: JSON.stringify(data) }),
  setPromotionStatus: (seasonId: string, id: string, status: "ACTIVE" | "REVOKED") =>
    request<void>(`/api/v1/seasons/${seasonId}/promotions/${id}`, {
      method: "PATCH",
      body: JSON.stringify({ status })
    }),
  teams: () => request<Team[]>("/api/v1/teams"),
  teamAthletes: (teamId: string) =>
    request<{ athleteId: string; fullName: string; status: string }[]>(`/api/v1/teams/${teamId}/athletes`),
  teamStaff: (teamId: string) => request<TeamStaff[]>(`/api/v1/teams/${teamId}/staff`),
  addTeamStaff: (teamId: string, data: { userId: string; role?: string }) =>
    request<TeamStaff[]>(`/api/v1/teams/${teamId}/staff`, { method: "POST", body: JSON.stringify(data) }),
  removeTeamStaff: (teamId: string, userId: string) =>
    request<void>(`/api/v1/teams/${teamId}/staff/${userId}`, { method: "DELETE" }),
  createTeam: (data: Record<string, unknown>) =>
    request<Team>("/api/v1/teams", { method: "POST", body: JSON.stringify(data) }),
  documents: (athleteId: string) => request<Document[]>(`/api/v1/athletes/${athleteId}/documents`),
  trainingSessions: () => request<TrainingSession[]>("/api/v1/training-sessions"),
  createTrainingSession: (data: Record<string, unknown>) =>
    request<TrainingSession>("/api/v1/training-sessions", { method: "POST", body: JSON.stringify(data) }),
  venues: () => request<{ id: string; name: string; address?: string }[]>("/api/v1/venues"),
  matches: () => request<Match[]>("/api/v1/matches"),
  createMatch: (data: Record<string, unknown>) =>
    request<Match>("/api/v1/matches", { method: "POST", body: JSON.stringify(data) }),
  feePlans: () => request<FeePlan[]>("/api/v1/fee-plans"),
  createFeePlan: (data: Record<string, unknown>) =>
    request<FeePlan>("/api/v1/fee-plans", { method: "POST", body: JSON.stringify(data) }),
  charges: (status?: string) =>
    request<Charge[]>(`/api/v1/charges${status ? `?status=${encodeURIComponent(status)}` : ""}`),
  createCharge: (data: Record<string, unknown>) =>
    request<Charge>("/api/v1/charges", { method: "POST", body: JSON.stringify(data) }),
  generateCharges: (data: Record<string, unknown>) =>
    request<{ period: string; created: number; skipped: number }>("/api/v1/charges/generate", {
      method: "POST",
      body: JSON.stringify(data)
    }),
  updateChargeStatus: (id: string, status: string) =>
    request<void>(`/api/v1/charges/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) }),
  payments: (chargeId: string) => request<Payment[]>(`/api/v1/charges/${chargeId}/payments`),
  createPayment: (data: Record<string, unknown>) =>
    request<Payment>("/api/v1/payments", { method: "POST", body: JSON.stringify(data) }),
  ledgerEntries: () => request<LedgerEntry[]>("/api/v1/ledger-entries"),
  createLedgerEntry: (data: Record<string, unknown>) =>
    request<LedgerEntry>("/api/v1/ledger-entries", { method: "POST", body: JSON.stringify(data) }),
  deleteLedgerEntry: (id: string) => request<void>(`/api/v1/ledger-entries/${id}`, { method: "DELETE" })
};
