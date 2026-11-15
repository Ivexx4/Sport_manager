export type Role = "ADMIN" | "DIRECAO" | "FINANCEIRO" | "TREINADOR" | "ATLETA" | "ENCARREGADO";

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
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

const API_BASE = import.meta.env.VITE_API_URL ?? "";
const ACCESS_KEY = "gestao.accessToken";
const REFRESH_KEY = "gestao.refreshToken";

export const authStore = {
  get access() { return localStorage.getItem(ACCESS_KEY); },
  get refresh() { return localStorage.getItem(REFRESH_KEY); },
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
      authStore.save(await refreshed.json() as TokenResponse);
      return request(path, init, false);
    }
    expireSession();
  } else if (response.status === 401) {
    expireSession();
  }
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Pedido falhou (${response.status})`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  login: (email: string, password: string) => request<TokenResponse>("/api/v1/auth/login", {
    method: "POST", body: JSON.stringify({ email, password })
  }),
  provisionTenant: (bootstrapKey: string, data: Record<string, unknown>) => request<ProvisionTenantResponse>("/api/v1/platform/tenants", {
    method: "POST",
    headers: { "X-Bootstrap-Key": bootstrapKey },
    body: JSON.stringify(data)
  }),
  me: () => request<{ userId: string; tenantId: string; roles: Role[] }>("/api/v1/auth/me"),
  logout: () => request<void>("/api/v1/auth/logout", {
    method: "POST", body: JSON.stringify({ refreshToken: authStore.refresh ?? "" })
  }),
  athletes: (params: { q?: string; status?: string; page?: number; size?: number } = {}) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => value !== undefined && query.set(key, String(value)));
    return request<Page<Athlete>>(`/api/v1/athletes?${query}`);
  },
  createAthlete: (data: Record<string, unknown>) => request<Athlete>("/api/v1/athletes", { method: "POST", body: JSON.stringify(data) }),
  updateAthlete: (id: string, data: Record<string, unknown>) => request<Athlete>(`/api/v1/athletes/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteAthlete: (id: string) => request<void>(`/api/v1/athletes/${id}`, { method: "DELETE" }),
  importAthletes: (file: File) => {
    const body = new FormData();
    body.append("file", file);
    return request<{ imported: number }>("/api/v1/athletes/import", { method: "POST", body });
  },
  seasons: () => request<Season[]>("/api/v1/seasons"),
  createSeason: (data: Record<string, unknown>) => request<Season>("/api/v1/seasons", { method: "POST", body: JSON.stringify(data) }),
  ageGroups: () => request<AgeGroup[]>("/api/v1/age-groups"),
  teams: () => request<Team[]>("/api/v1/teams"),
  teamAthletes: (teamId: string) => request<{ athleteId: string; fullName: string; status: string }[]>(`/api/v1/teams/${teamId}/athletes`),
  teamStaff: (teamId: string) => request<TeamStaff[]>(`/api/v1/teams/${teamId}/staff`),
  addTeamStaff: (teamId: string, data: { userId: string; role?: string }) => request<TeamStaff[]>(`/api/v1/teams/${teamId}/staff`, { method: "POST", body: JSON.stringify(data) }),
  removeTeamStaff: (teamId: string, userId: string) => request<void>(`/api/v1/teams/${teamId}/staff/${userId}`, { method: "DELETE" }),
  createTeam: (data: Record<string, unknown>) => request<Team>("/api/v1/teams", { method: "POST", body: JSON.stringify(data) }),
  documents: (athleteId: string) => request<Document[]>(`/api/v1/athletes/${athleteId}/documents`),
  trainingSessions: () => request<TrainingSession[]>("/api/v1/training-sessions"),
  createTrainingSession: (data: Record<string, unknown>) => request<TrainingSession>("/api/v1/training-sessions", { method: "POST", body: JSON.stringify(data) }),
  venues: () => request<{ id: string; name: string; address?: string }[]>("/api/v1/venues"),
  matches: () => request<Match[]>("/api/v1/matches"),
  createMatch: (data: Record<string, unknown>) => request<Match>("/api/v1/matches", { method: "POST", body: JSON.stringify(data) }),
  feePlans: () => request<FeePlan[]>("/api/v1/fee-plans"),
  createFeePlan: (data: Record<string, unknown>) => request<FeePlan>("/api/v1/fee-plans", { method: "POST", body: JSON.stringify(data) }),
  charges: (status?: string) => request<Charge[]>(`/api/v1/charges${status ? `?status=${encodeURIComponent(status)}` : ""}`),
  createCharge: (data: Record<string, unknown>) => request<Charge>("/api/v1/charges", { method: "POST", body: JSON.stringify(data) }),
  generateCharges: (data: Record<string, unknown>) => request<{ period: string; created: number; skipped: number }>("/api/v1/charges/generate", { method: "POST", body: JSON.stringify(data) }),
  updateChargeStatus: (id: string, status: string) => request<void>(`/api/v1/charges/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) }),
  payments: (chargeId: string) => request<Payment[]>(`/api/v1/charges/${chargeId}/payments`),
  createPayment: (data: Record<string, unknown>) => request<Payment>("/api/v1/payments", { method: "POST", body: JSON.stringify(data) }),
  ledgerEntries: () => request<LedgerEntry[]>("/api/v1/ledger-entries"),
  createLedgerEntry: (data: Record<string, unknown>) => request<LedgerEntry>("/api/v1/ledger-entries", { method: "POST", body: JSON.stringify(data) }),
  deleteLedgerEntry: (id: string) => request<void>(`/api/v1/ledger-entries/${id}`, { method: "DELETE" })
};
