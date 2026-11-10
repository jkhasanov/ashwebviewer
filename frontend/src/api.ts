export type Role = 'ADMIN' | 'VIEWER'
export type DbType = 'ORACLE' | 'POSTGRES'
export type CollectorState = 'STOPPED' | 'STARTING' | 'RUNNING' | 'ERROR'

export interface Me {
  username: string
  role: Role
}

export interface CollectorStatus {
  state: CollectorState
  lastSampleTime: string | null
  lastSuccessAt: string | null
  lastError: string | null
  lastErrorAt: string | null
  rowsCollected: number
  /** Database clock minus the server's clock, in seconds. 0 when they agree. */
  clockSkewSeconds: number
}

export interface Target {
  id: number
  name: string
  dbType: DbType
  jdbcUrl: string
  username: string
  enabled: boolean
  collector: CollectorStatus
  avgActiveSessions: number | null
  byWaitClass: Record<string, number>
  spark: number[] | null
}

export interface TargetInput {
  name: string
  dbType: DbType
  jdbcUrl: string
  username: string
  password: string
  enabled: boolean
}

export interface TimeSeries {
  from: string
  to: string
  bucketSeconds: number
  groupedBy: 'wait_class' | 'event'
  times: string[]
  series: { name: string; values: number[] }[]
}

export interface TopRow {
  sqlId: string | null
  sqlOpname: string | null
  sqlText: string | null
  sessionId: number | null
  sessionSerial: number | null
  userName: string | null
  program: string | null
  samples: number
  percent: number
  avgActiveSessions: number
  byWaitClass: Record<string, number>
}

export interface TopResult {
  from: string
  to: string
  totalSamples: number
  rows: TopRow[]
}

export interface SqlInfo {
  sqlId: string
  sqlOpname: string | null
  sqlText: string | null
}

export interface SqlStat {
  name: string
  unit: 'count' | 'micros' | 'millis' | 'text'
  number: number | null
  text: string | null
}

export interface SqlPlanRow {
  id: number
  parentId: number | null
  depth: number
  operation: string
  object: string | null
  rows: number | null
  bytes: number | null
  cost: number | null
  timeSeconds: number | null
  accessPredicates: string | null
  filterPredicates: string | null
}

/** Plans and statistics read from the monitored database on demand. */
export interface SqlLiveDetail {
  plans: { planHashValue: string; childNumber: number; rows: SqlPlanRow[] }[]
  stats: { label: string; executions: number | null; stats: SqlStat[] }[]
  notes: string[]
}

export interface User {
  id: number
  username: string
  role: Role
  enabled: boolean
  createdAt: string
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const token = csrfToken()
  if (token && method !== 'GET') headers['X-XSRF-TOKEN'] = token

  const response = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = null
    }
  }
  if (!response.ok) {
    const message =
      (data as { message?: string } | null)?.message ??
      (response.status === 403
        ? 'You do not have permission to do that.'
        : response.status === 401
          ? 'You are signed out. Sign in again.'
          : `The server answered with an error (${response.status}).`)
    if (response.status === 401 && !path.startsWith('/api/auth/')) {
      window.dispatchEvent(new Event('ashweb:signed-out'))
    }
    throw new ApiError(response.status, message)
  }
  return data as T
}

export const query = (params: Record<string, string | number | null | undefined>) =>
  Object.entries(params)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join('&')
