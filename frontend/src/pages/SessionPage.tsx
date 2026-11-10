import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, query, reportLink, samplesLink } from '../api'
import type { SessionInfo, Target, TimeSeries, TopResult } from '../api'
import { sortWaitClasses, waitClassColor } from '../colors'
import { ActivityChart } from '../components/ActivityChart'
import { TopTable } from '../components/TopTable'
import { formatDuration, formatRange } from '../format'
import { useTheme } from '../theme'

const HOUR = 3600_000
const RANGES = [
  { label: '1 h', ms: HOUR },
  { label: '8 h', ms: 8 * HOUR },
  { label: '1 day', ms: 24 * HOUR },
  { label: '1 week', ms: 7 * 24 * HOUR },
]

/** The stored per-sample details that describe the session itself, in display order. */
const ATTRIBUTE_LABELS: [string, string][] = [
  ['machine', 'Machine'],
  ['module', 'Module'],
  ['action', 'Action'],
  ['sessionType', 'Session type'],
  ['database', 'Database'],
  ['client', 'Client address'],
  ['backendType', 'Backend type'],
]

const dateTime = (ms: number) =>
  new Date(ms).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function SessionPage() {
  const params = useParams()
  const id = Number(params.id)
  const sessionId = Number(params.sessionId)
  const sessionSerial = Number(params.sessionSerial)
  const theme = useTheme()
  const [search] = useSearchParams()

  // The period comes from the page that linked here; the buttons replace it with "the last ..."
  const initial = useMemo<[number, number]>(() => {
    const from = Number(search.get('from'))
    const to = Number(search.get('to'))
    return from > 0 && to > from ? [from, to] : [Date.now() - HOUR, Date.now()]
    // Only read once: later range changes are local state
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const [range, setRange] = useState<[number, number]>(initial)
  const [rangeLabel, setRangeLabel] = useState<string | null>(null)
  const [selection, setSelection] = useState<[number, number] | null>(null)
  const [clearSignal, setClearSignal] = useState(0)

  const clearSelection = () => {
    setSelection(null)
    setClearSignal((n) => n + 1)
  }
  const chooseRange = (label: string | null, next: [number, number]) => {
    setRange(next)
    setRangeLabel(label)
    clearSelection()
  }

  const target = useQuery({ queryKey: ['target', id], queryFn: () => api<Target>('GET', `/api/targets/${id}`) })
  const info = useQuery({
    queryKey: ['session-info', id, sessionId, sessionSerial],
    queryFn: () => api<SessionInfo>('GET', `/api/targets/${id}/session/${sessionId}/${sessionSerial}`),
    retry: false,
  })
  const iso = (ms: number) => new Date(ms).toISOString()
  const activity = useQuery({
    queryKey: ['session-activity', id, sessionId, sessionSerial, range],
    queryFn: () => api<TimeSeries>('GET', `/api/targets/${id}/activity?` + query({
      from: iso(range[0]), to: iso(range[1]), sessionId, sessionSerial,
    })),
    placeholderData: keepPreviousData,
  })
  const period = selection ?? range
  const statements = useQuery({
    queryKey: ['session-sql', id, sessionId, sessionSerial, period],
    queryFn: () => api<TopResult>('GET', `/api/targets/${id}/top?` + query({
      dimension: 'SQL', from: iso(period[0]), to: iso(period[1]), sessionId, sessionSerial,
    })),
    placeholderData: keepPreviousData,
  })

  const data = activity.data
  const chart = useMemo(() => {
    if (!data) return null
    const byName = new Map(data.series.map((s) => [s.name, s.values]))
    return {
      times: data.times.map((t) => new Date(t).getTime()),
      series: sortWaitClasses([...byName.keys()]).map((name) => ({
        name, color: waitClassColor(name), values: byName.get(name)!,
      })),
    }
    // theme: wait class colours differ between light and dark styles
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, theme])

  const oracle = target.data?.dbType === 'ORACLE'
  const s = info.data
  const firstSeen = s ? new Date(s.firstSeen).getTime() : 0
  // Activity is stored per minute, so the last active minute ends 60 s after it starts
  const lastSeen = s ? new Date(s.lastSeen).getTime() + 60_000 : 0

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs">
            <Link to="/">Overview</Link> / <Link to={`/db/${id}`}>{target.data?.name ?? '…'}</Link> /
          </div>
          <h1>
            Session <span className="mono sql-id-title">{sessionId}{oracle && `,${sessionSerial}`}</span>
          </h1>
        </div>
        {target.data && <span className="muted">{oracle ? 'sid, serial#' : 'backend process id'}</span>}
      </div>

      <section className="panel">
        <div className="panel-head">
          <h2>About this session</h2>
          {s && (
            <button onClick={() => chooseRange(null, [firstSeen, Math.min(lastSeen, Date.now())])}>
              Show its whole active period
            </button>
          )}
        </div>
        {info.isPending && <p className="muted pad">Loading…</p>}
        {info.error && <p className="error-text pad">{info.error.message}</p>}
        {s && (
          <dl className="facts">
            <div><dt>User</dt><dd>{s.userName ?? '—'}</dd></div>
            <div><dt>Program</dt><dd>{s.program ?? '—'}</dd></div>
            {ATTRIBUTE_LABELS.filter(([key]) => s.attributes[key]).map(([key, label]) => (
              <div key={key}><dt>{label}</dt><dd>{s.attributes[key]}</dd></div>
            ))}
            {target.data && !oracle && (
              <div><dt>Connected since</dt><dd>{dateTime(sessionSerial * 1000)}</dd></div>
            )}
            <div><dt>First seen active</dt><dd>{dateTime(firstSeen)}</dd></div>
            <div><dt>Last seen active</dt><dd>{dateTime(lastSeen - 60_000)}</dd></div>
            <div>
              <dt title="One sample is one second in which the session was working or waiting">Time active</dt>
              <dd>{formatDuration(s.samples * 1000)}</dd>
            </div>
          </dl>
        )}
      </section>

      <div className="filters">
        <div className="segmented" role="group" aria-label="Time range">
          {RANGES.map((r) => (
            <button key={r.label} className={rangeLabel === r.label ? 'active' : ''}
                    onClick={() => chooseRange(r.label, [Date.now() - r.ms, Date.now()])}>
              Last {r.label}
            </button>
          ))}
        </div>
        <span className="muted">{formatRange(range[0], range[1])} ({formatDuration(range[1] - range[0])})</span>
      </div>

      <section className="panel">
        <div className="panel-head">
          <h2>Activity of this session</h2>
          <span className="muted">{data && `${formatDuration(data.bucketSeconds * 1000)} per point`}</span>
        </div>
        {activity.error && <p className="error-text pad">{activity.error.message}</p>}
        {chart && (
          <div className="chart-wrap">
            <ActivityChart times={chart.times} bucketSeconds={data!.bucketSeconds} series={chart.series}
                           dark={theme.dark} theme={theme.id} onSelect={setSelection} clearSignal={clearSignal} />
            {chart.series.length === 0 && <div className="chart-empty muted">This session was not active in this period.</div>}
          </div>
        )}
        <p className="hint muted">
          1 means the session was busy the whole time. Drag across the chart to narrow the statements below to that period.
        </p>
      </section>

      <div className="period-bar">
        <span>
          <strong>{selection ? 'Selected period' : 'Whole range'}</strong> · {formatRange(period[0], period[1])} ({formatDuration(period[1] - period[0])})
        </span>
        <span className="period-actions">
          {selection && <button onClick={clearSelection}>Clear selection</button>}
          <Link to={samplesLink(id, period, { sessionId, sessionSerial })}>View samples</Link>
          {oracle && <Link to={reportLink(id, period, { sessionId })}>ASH report</Link>}
        </span>
      </div>
      <TopTable kind="SQL" title="Statements it ran" result={statements.data} loading={statements.isPending}
                error={statements.error?.message ?? null}
                sqlLink={(sqlId) => `/db/${id}/sql/${encodeURIComponent(sqlId)}?from=${range[0]}&to=${range[1]}`} />
    </>
  )
}
