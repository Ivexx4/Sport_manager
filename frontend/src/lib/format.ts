/** Formatação de datas e dinheiro no formato português, num só lugar. */

export function formatDate(value?: string) {
  return value ? new Intl.DateTimeFormat("pt-PT", { dateStyle: "medium" }).format(new Date(`${value}T12:00:00`)) : "—";
}

export function formatDateTime(value?: string) {
  return value ? new Intl.DateTimeFormat("pt-PT", { dateStyle: "medium" }).format(new Date(value)) : "—";
}

export function formatTime(value?: string) {
  return value ? new Intl.DateTimeFormat("pt-PT", { hour: "2-digit", minute: "2-digit" }).format(new Date(value)) : "—";
}

export function formatMoney(cents: number) {
  return new Intl.NumberFormat("pt-PT", { style: "currency", currency: "EUR" }).format(cents / 100);
}
