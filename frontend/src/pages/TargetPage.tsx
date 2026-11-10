import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, query } from '../api'
import type { Target, TimeSeries, TopResult } from '../api'
import { MAX_EVENT_SERIES, OTHER_EVENTS, eventColor, sortWaitClasses, waitClassColor } from '../colors'
import { ActivityChart } from '../components/ActivityChart'
import type { ChartSeries } from '../components/ActivityChart'
import { StatusBadge } from '../components/StatusBadge'
import { TopTable } from '../components/TopTable'
import { DB_TYPE_LABEL, formatDuration, formatRange, toLocalInput } from '../format'
import { useTheme } from '../theme'

const HOUR = 3600_000
const PRESETS = [
  { key: 'live', label: 'Live (1 h)', ms: HOUR },
  { key: '8h', label: '8 h', ms: 8 * HOUR },
  { key: '12h', label: '12 h', ms: 12 * HOUR },
  { key: '1d', label: '1 day', ms: 24 * HOUR },
  { key: '1w', label: '1 week', ms: 7 * 24 * HOUR },
  { key: '1m', label: '1 month', ms: 30 * 24 * HOUR },
  { key: 'custom', label: 'Custom', ms: 0 },
] as const
type PresetKey = (typeof PRESETS)[number]['key']

const LIVE_REFRESH_MS = 5000
/** With nothing selected, the live view ranks the last five minutes. */
const LIVE_TOP_WINDOW_MS = 5 * 60_000

const WAIT_CLASSES = {
  ORACLE: ['CPU', 'Scheduler', 'User I/O', 'System I/O', 'Concurrency', 'Application', 'Commit', 'Configuration',
    'Administrative', 'Network', 'Queueing', 'Cluster', 'Other'],
  POSTGRES: ['CPU', 'IO', 'Lock', 'LWLock', 'BufferPin', 'Activity', 'Extension', 'Client', 'IPC', 'Timeout'],
} as const

export function TargetPage() {
  const id = Number(useParams().id)
  const theme = useTheme()
  const dark = theme.dark

  const [preset, setPreset] = useState<PresetKey>('live')
  /** "Now" at the moment a history preset was chosen, so the window does not slide while it is studied. */
  const [anchor, setAnchor] = useState(() => Date.now())
  const [customFrom, setCustomFrom] = useState(() => toLocalInput(Date.now() - 2 * HOUR))
  const [customTo, setCustomTo] = useState(() => toLocalInput(Date.now()))
  const [appliedCustom, setAppliedCustom] = useState<[number, number] | null>(null)
  const [waitClass, setWaitClass] = useState('')
  const [selection, setSelection] = useState<[number, number] | null>(null)
  const [clearSignal, setClearSignal] = useState(0)

  const live = preset === 'live'
  const customError = preset === 'custom' && new Date(customFrom).getTime() >= new Date(customTo).getTime()
    ? 'The start must be before the end.'
    : null

  const clearSelection = () => {
    setSelection(null)
    setClearSignal((n) => n + 1)
  }
  const choosePreset = (key: PresetKey) => {
    setPreset(key)
    setAnchor(Date.now())
    clearSelection()
  }

  const target = useQuery({
    queryKey: ['target', id],
    queryFn: () => api<Target>('GET', `/api/targets/${id}`),
    refetchInterval: 10_000,
  })

  const range = (): [number, number] | null => {
    if (live) return [Date.now() - HOUR, Date.now()]
    if (preset === 'custom') return appliedCustom
    const ms = PRESETS.find((p) => p.key === preset)!.ms
    return [anchor - ms, anchor]
  }
  const fixedRange = live ? null : range()

  const activity = useQuery({
    queryKey: ['activity', id, preset, live ? 0 : fixedRange?.[0], live ? 0 : fixedRange?.[1], waitClass],
    queryFn: () => {
      const [from, to] = range()!
      return api<TimeSeries>('GET', `/api/targets/${id}/activity?` + query({
        from: new Date(from).toISOString(), to: new Date(to).toISOString(), waitClass,
      }))
    },
    enabled: live || fixedRange !== null,
    refetchInterval: live ? LIVE_REFRESH_MS : false,
    placeholderData: keepPreviousData,
  })

  // The period the Top tables describe: the dragged selection, else a sensible default for the view
  const topPeriod = (): [number, number] | null => {
    if (selection) return selection
    if (live) return [Date.now() - LIVE_TOP_WINDOW_MS, Date.now()]
    return fixedRange
  }
  const topKey = selection ?? (live ? 'live' : fixedRange)
  const useTop = (dimension: 'SQL' | 'SESSION') =>
    useQuery({
      queryKey: ['top', id, dimension, topKey, waitClass],
      queryFn: () => {
        const [from, to] = topPeriod()!
        return api<TopResult>('GET', `/api/targets/${id}/top?` + query({
          dimension, from: new Date(from).toISOString(), to: new Date(to).toISOString(), waitClass,
        }))
      },
      enabled: selection !== null || live || fixedRange !== null,
      refetchInterval: live && !selection ? LIVE_REFRESH_MS : false,
      placeholderData: keepPreviousData,
    })
  const topSql = useTop('SQL')
  const topSessions = useTop('SESSION')

  // An event keeps the colour it was first given while this wait class is on screen, even when the
  // ranking changes between refreshes
  const eventSlots = useRef(new Map<string, number>())
  useEffect(() => {
    eventSlots.current = new Map()
  }, [id, waitClass])

  const data = activity.data
  const chart = useMemo(() => {
    if (!data) return null
    const times = data.times.map((t) => new Date(t).getTime())
    let series: ChartSeries[]
    if (data.groupedBy === 'wait_class') {
      const byName = new Map(data.series.map((s) => [s.name, s.values]))
      series = sortWaitClasses([...byName.keys()]).map((name) => ({
        name, color: waitClassColor(name), values: byName.get(name)!,
      }))
    } else {
      const slots = eventSlots.current
      const other = new Array<number>(times.length).fill(0)
      let hasOther = false
      series = []
      for (const s of data.series) {
        if (!slots.has(s.name) && slots.size < MAX_EVENT_SERIES) slots.set(s.name, slots.size)
        const slot = slots.get(s.name)
        if (slot === undefined) {
          s.values.forEach((v, i) => (other[i] += v))
          hasOther = true
        } else {
          series.push({ name: s.name, color: eventColor(slot, dark), values: s.values })
        }
      }
      series.sort((a, b) => slots.get(a.name)! - slots.get(b.name)!)
      if (hasOther) series.push({ name: OTHER_EVENTS, color: eventColor(MAX_EVENT_SERIES, dark), values: other })
    }
    return { times, series }
  }, [data, theme])

  if (target.error) {
    return <p className="error-text pad">{target.error.message} <Link to="/">Back to overview</Link></p>
  }
  const t = target.data
  const period = selection ?? (topSql.data ? [new Date(topSql.data.from).getTime(), new Date(topSql.data.to).getTime()] as [number, number] : null)
  const noData = chart !== null && chart.series.length === 0
  // A statement's page opens on the same stretch of time this chart shows
  const sqlLink = (sqlId: string) => {
    const from = data ? new Date(data.from).getTime() : Date.now() - HOUR
    const to = data ? new Date(data.to).getTime() : Date.now()
    return `/db/${id}/sql/${encodeURIComponent(sqlId)}?from=${from}&to=${to}`
  }

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs"><Link to="/">Overview</Link> /</div>
          <h1>{t?.name ?? '…'}</h1>
        </div>
        {t && (
          <div className="page-head-meta">
            <span className="muted">{DB_TYPE_LABEL[t.dbType]}</span>
            <StatusBadge status={t.collector} />
          </div>
        )}
      </div>
      {t?.collector.state === 'ERROR' && (
        <p className="banner banner-error">Collection is failing: {t.collector.lastError}</p>
      )}
      {t && Math.abs(t.collector.clockSkewSeconds) >= 60 && (
        <p className="banner">
          This database’s clock is {formatDuration(Math.abs(t.collector.clockSkewSeconds) * 1000)}{' '}
          {t.collector.clockSkewSeconds > 0 ? 'ahead of' : 'behind'} this server’s clock. Times on this page are
          in server time, so they differ from the database’s own timestamps by that much.
        </p>
      )}
      {t?.collector.state === 'STOPPED' && (
        <p className="banner">Collection is stopped for this database. The charts show what was stored earlier.</p>
      )}

      <div className="filters">
        <div className="segmented" role="group" aria-label="Time range">
          {PRESETS.map((p) => (
            <button key={p.key} className={preset === p.key ? 'active' : ''} onClick={() => choosePreset(p.key)}>
              {p.label}
            </button>
          ))}
        </div>
        {preset === 'custom' && (
          <form className="custom-range" onSubmit={(e) => {
            e.preventDefault()
            if (!customError) {
              setAppliedCustom([new Date(customFrom).getTime(), new Date(customTo).getTime()])
              clearSelection()
            }
          }}>
            <label>From <input type="datetime-local" value={customFrom} onChange={(e) => setCustomFrom(e.target.value)} required /></label>
            <label>To <input type="datetime-local" value={customTo} onChange={(e) => setCustomTo(e.target.value)} required /></label>
            <button type="submit" className="primary" disabled={!!customError}>Load</button>
            {customError && <span className="error-text">{customError}</span>}
          </form>
        )}
        {!live && preset !== 'custom' && (
          <button onClick={() => choosePreset(preset)}>Refresh</button>
        )}
        <label className="filter-select">Wait class
          <select value={waitClass} onChange={(e) => setWaitClass(e.target.value)}>
            <option value="">All (stacked by wait class)</option>
            {(t ? WAIT_CLASSES[t.dbType] : []).map((c) => <option key={c} value={c}>{c} (by event)</option>)}
          </select>
        </label>
      </div>

      <section className="panel">
        <div className="panel-head">
          <h2>{waitClass ? `Active sessions waiting on ${waitClass}, by event` : 'Active sessions by wait class'}</h2>
          <span className="muted">
            {data && `${formatDuration(data.bucketSeconds * 1000)} per point`}
            {live && ' · updates every 5 s'}
          </span>
        </div>
        {activity.error && <p className="error-text pad">{activity.error.message}</p>}
        {preset === 'custom' && !appliedCustom && <p className="muted pad">Choose a start and end, then Load.</p>}
        {chart && (
          <div className="chart-wrap">
            <ActivityChart times={chart.times} bucketSeconds={data!.bucketSeconds} series={chart.series} dark={dark} theme={theme.id}
                           onSelect={setSelection} clearSignal={clearSignal} />
            {noData && <div className="chart-empty muted">No activity recorded in this period.</div>}
          </div>
        )}
        <p className="hint muted">Drag across the chart to see the top SQL and sessions for that period.</p>
      </section>

      <div className="period-bar">
        <span>
          <strong>{selection ? 'Selected period' : live ? 'Last 5 minutes' : 'Whole range'}</strong>
          {period && <> · {formatRange(period[0], period[1])} ({formatDuration(period[1] - period[0])})</>}
          {waitClass && <> · {waitClass} only</>}
        </span>
        {selection && <button onClick={clearSelection}>Clear selection</button>}
      </div>

      <div className="top-grid">
        <TopTable kind="SQL" result={topSql.data} loading={topSql.isPending} error={topSql.error?.message ?? null}
                  sqlLink={sqlLink} />
        <TopTable kind="SESSION" result={topSessions.data} loading={topSessions.isPending}
                  error={topSessions.error?.message ?? null} showSerial={t?.dbType === 'ORACLE'} />
      </div>

    </>
  )
}
