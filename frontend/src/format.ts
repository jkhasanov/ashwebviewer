export function formatAas(value: number | null | undefined): string {
  if (value === null || value === undefined) return '–'
  if (value === 0) return '0'
  if (value < 0.01) return '<0.01'
  return value < 10 ? value.toFixed(2) : value < 100 ? value.toFixed(1) : Math.round(value).toString()
}

export function formatAgo(iso: string | null, now = Date.now()): string {
  if (!iso) return 'never'
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (seconds < 5) return 'just now'
  if (seconds < 90) return `${seconds} s ago`
  if (seconds < 5400) return `${Math.round(seconds / 60)} min ago`
  if (seconds < 129600) return `${Math.round(seconds / 3600)} h ago`
  return `${Math.round(seconds / 86400)} d ago`
}

const two = (n: number) => String(n).padStart(2, '0')

export function formatTime(ms: number, withSeconds = true): string {
  const d = new Date(ms)
  return `${two(d.getHours())}:${two(d.getMinutes())}${withSeconds ? ':' + two(d.getSeconds()) : ''}`
}

export function formatDateTime(ms: number, withSeconds = true): string {
  const d = new Date(ms)
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())} ${formatTime(ms, withSeconds)}`
}

export function formatRange(from: number, to: number): string {
  const sameDay = new Date(from).toDateString() === new Date(to).toDateString()
  const today = new Date(from).toDateString() === new Date().toDateString()
  const start = sameDay && today ? formatTime(from) : formatDateTime(from)
  const end = sameDay ? formatTime(to) : formatDateTime(to)
  return `${start} – ${end}`
}

export function formatDuration(ms: number): string {
  const s = Math.round(ms / 1000)
  if (s < 120) return `${s} s`
  if (s < 7200) return `${Math.round(s / 60)} min`
  if (s < 172800) return `${(s / 3600).toFixed(1).replace(/\.0$/, '')} h`
  return `${(s / 86400).toFixed(1).replace(/\.0$/, '')} d`
}

/** Value for an <input type="datetime-local"> in the browser's time zone. */
export function toLocalInput(ms: number): string {
  const d = new Date(ms)
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}`
}

export const DB_TYPE_LABEL = { ORACLE: 'Oracle', POSTGRES: 'PostgreSQL' } as const
