import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, query, samplesLink } from '../api'
import type { SqlInfo, SqlLiveDetail, SqlStat, Target, TimeSeries, TopResult } from '../api'
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

function formatCount(value: number): string {
  if (value >= 100 || Number.isInteger(value)) return Math.round(value).toLocaleString()
  return value.toLocaleString(undefined, { maximumFractionDigits: 2 })
}

/** A duration given in microseconds, in the largest unit that keeps it readable. */
function formatMicros(micros: number): string {
  if (micros === 0) return '0'
  const ms = micros / 1000
  if (ms < 1) return `${micros.toFixed(0)} µs`
  if (ms < 1000) return `${ms.toFixed(ms < 10 ? 2 : 1)} ms`
  const s = ms / 1000
  if (s < 120) return `${s.toFixed(s < 10 ? 2 : 1)} s`
  if (s < 7200) return `${(s / 60).toFixed(1)} min`
  return `${(s / 3600).toFixed(1)} h`
}

function formatStat(stat: SqlStat, divisor = 1): string {
  if (stat.unit === 'text' || stat.number === null) return stat.text ?? ''
  const value = stat.number / divisor
  if (stat.unit === 'micros') return formatMicros(value)
  if (stat.unit === 'millis') return formatMicros(value * 1000)
  return formatCount(value)
}

function shortNumber(value: number | null): string {
  if (value === null) return ''
  if (value < 10_000) return value.toLocaleString()
  if (value < 10_000_000) return `${(value / 1000).toFixed(0)}K`
  if (value < 10_000_000_000) return `${(value / 1_000_000).toFixed(0)}M`
  return `${(value / 1_000_000_000).toFixed(0)}G`
}

function PlanTable({ plan }: { plan: SqlLiveDetail['plans'][number] }) {
  return (
    <div className="table-scroll">
      <table className="data-table plan-table">
        <thead>
          <tr>
            <th className="num">Id</th>
            <th>Operation</th>
            <th>Object</th>
            <th className="num" title="Rows the optimizer expects">Est. rows</th>
            <th className="num" title="Bytes the optimizer expects">Est. bytes</th>
            <th className="num" title="Optimizer cost">Cost</th>
            <th>Predicates</th>
          </tr>
        </thead>
        <tbody>
          {plan.rows.map((row) => (
            <tr key={row.id}>
              <td className="num muted">{row.id}</td>
              <td className="mono plan-op" style={{ paddingLeft: 14 + row.depth * 14 }}>{row.operation}</td>
              <td className="mono">{row.object ?? ''}</td>
              <td className="num">{shortNumber(row.rows)}</td>
              <td className="num">{shortNumber(row.bytes)}</td>
              <td className="num">{shortNumber(row.cost)}</td>
              <td className="mono plan-predicates">
                {row.accessPredicates && <div><span className="muted">access: </span>{row.accessPredicates}</div>}
                {row.filterPredicates && <div><span className="muted">filter: </span>{row.filterPredicates}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function SqlPage() {
  const params = useParams()
  const id = Number(params.id)
  const sqlId = params.sqlId!
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
  const [planIndex, setPlanIndex] = useState(0)
  const [copied, setCopied] = useState(false)

  const clearSelection = () => {
    setSelection(null)
    setClearSignal((n) => n + 1)
  }
  const chooseRange = (label: string, ms: number) => {
    setRange([Date.now() - ms, Date.now()])
    setRangeLabel(label)
    clearSelection()
  }

  const target = useQuery({ queryKey: ['target', id], queryFn: () => api<Target>('GET', `/api/targets/${id}`) })
  const info = useQuery({
    queryKey: ['sql-info', id, sqlId],
    queryFn: () => api<SqlInfo>('GET', `/api/targets/${id}/sql/${encodeURIComponent(sqlId)}`),
  })
  const live = useQuery({
    queryKey: ['sql-live', id, sqlId],
    queryFn: () => api<SqlLiveDetail>('GET', `/api/targets/${id}/sql/${encodeURIComponent(sqlId)}/live`),
  })
  const iso = (ms: number) => new Date(ms).toISOString()
  const activity = useQuery({
    queryKey: ['sql-activity', id, sqlId, range],
    queryFn: () => api<TimeSeries>('GET', `/api/targets/${id}/activity?` + query({ from: iso(range[0]), to: iso(range[1]), sqlId })),
    placeholderData: keepPreviousData,
  })
  const period = selection ?? range
  const sessions = useQuery({
    queryKey: ['sql-sessions', id, sqlId, period],
    queryFn: () => api<TopResult>('GET', `/api/targets/${id}/top?` + query({
      dimension: 'SESSION', from: iso(period[0]), to: iso(period[1]), sqlId,
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

  const plans = live.data?.plans ?? []
  const plan = plans[Math.min(planIndex, plans.length - 1)]
  const text = info.data?.sqlText ?? null

  const copy = async () => {
    if (!text) return
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard access can be refused; the text can still be selected by hand
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs">
            <Link to="/">Overview</Link> / <Link to={`/db/${id}`}>{target.data?.name ?? '…'}</Link> /
          </div>
          <h1>SQL <span className="mono sql-id-title">{sqlId}</span></h1>
        </div>
        {info.data?.sqlOpname && <span className="muted">{info.data.sqlOpname}</span>}
      </div>

      <section className="panel">
        <div className="panel-head">
          <h2>Statement</h2>
          {text && <button onClick={copy}>{copied ? 'Copied' : 'Copy'}</button>}
        </div>
        {info.error && <p className="error-text pad">{info.error.message}</p>}
        {info.data && !text && (
          <p className="muted pad">The text of this statement was not captured. It may have left the database’s memory before it could be read.</p>
        )}
        {text && <pre className="sql-full sql-inline">{text}</pre>}
      </section>

      <div className="filters">
        <div className="segmented" role="group" aria-label="Time range">
          {RANGES.map((r) => (
            <button key={r.label} className={rangeLabel === r.label ? 'active' : ''} onClick={() => chooseRange(r.label, r.ms)}>
              Last {r.label}
            </button>
          ))}
        </div>
        <span className="muted">{formatRange(range[0], range[1])} ({formatDuration(range[1] - range[0])})</span>
      </div>

      <section className="panel">
        <div className="panel-head">
          <h2>Active sessions running this statement</h2>
          <span className="muted">{data && `${formatDuration(data.bucketSeconds * 1000)} per point`}</span>
        </div>
        {activity.error && <p className="error-text pad">{activity.error.message}</p>}
        {chart && (
          <div className="chart-wrap">
            <ActivityChart times={chart.times} bucketSeconds={data!.bucketSeconds} series={chart.series}
                           dark={theme.dark} theme={theme.id} onSelect={setSelection} clearSignal={clearSignal} />
            {chart.series.length === 0 && <div className="chart-empty muted">This statement was not active in this period.</div>}
          </div>
        )}
        <p className="hint muted">Drag across the chart to narrow the sessions below to that period.</p>
      </section>

      <div className="period-bar">
        <span>
          <strong>{selection ? 'Selected period' : 'Whole range'}</strong> · {formatRange(period[0], period[1])} ({formatDuration(period[1] - period[0])})
        </span>
        <span className="period-actions">
          {selection && <button onClick={clearSelection}>Clear selection</button>}
          <Link to={samplesLink(id, period, { sqlId })}>View samples</Link>
        </span>
      </div>
      <TopTable kind="SESSION" title="Sessions that ran it" result={sessions.data} loading={sessions.isPending}
                error={sessions.error?.message ?? null}
                sessionLink={(sid, serial) => `/db/${id}/session/${sid}/${serial}?from=${range[0]}&to=${range[1]}`}
                showSerial={target.data?.dbType === 'ORACLE'} />

      <section className="panel">
        <div className="panel-head">
          <h2>Execution plan</h2>
          <span className="muted">Read from the database just now</span>
        </div>
        {live.isPending && <p className="muted pad">Asking the database…</p>}
        {live.error && <p className="error-text pad">{live.error.message}</p>}
        {plans.length > 1 && (
          <div className="tabs" role="tablist" aria-label="Plans">
            {plans.map((p, i) => (
              <button key={p.planHashValue} role="tab" aria-selected={plan === p} className={plan === p ? 'active' : ''}
                      onClick={() => setPlanIndex(i)}>
                Plan {p.planHashValue}
              </button>
            ))}
          </div>
        )}
        {plan && plans.length === 1 && <p className="muted plan-caption">Plan hash value {plan.planHashValue}</p>}
        {plan && <PlanTable plan={plan} />}
        {live.data?.notes.map((note) => <p key={note} className="muted pad note">{note}</p>)}
      </section>

      {(live.data?.stats.length ?? 0) > 0 && (
        <section className="panel">
          <div className="panel-head">
            <h2>Statistics</h2>
            <span className="muted">Totals since the statement was loaded, as the database reports them</span>
          </div>
          <div className="stats-grid">
            {live.data!.stats.map((group) => (
              <div key={group.label} className="table-scroll">
                <table className="data-table">
                  <thead>
                    <tr><th>{group.label}</th><th className="num">Total</th><th className="num">Per execution</th></tr>
                  </thead>
                  <tbody>
                    {group.stats.map((stat) => (
                      <tr key={stat.name}>
                        <td>{stat.name}</td>
                        <td className="num">{formatStat(stat)}</td>
                        <td className="num">
                          {stat.name !== 'Executions' && stat.unit !== 'text' && group.executions
                            ? formatStat(stat, group.executions)
                            : ''}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  )
}
