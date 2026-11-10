import { formatAas } from '../format'

/** Small load-over-the-last-hour chart for a table cell. One value per minute, oldest first. */
export function Sparkline({ values }: { values: number[] | null }) {
  const width = 140
  const height = 28
  if (!values || values.length === 0 || values.every((v) => v === 0)) {
    return <svg className="sparkline" width={width} height={height} aria-hidden="true" />
  }
  const max = Math.max(...values, 1)
  const step = width / values.length
  const y = (v: number) => height - 1 - (v / max) * (height - 3)
  let line = ''
  values.forEach((v, i) => {
    line += `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(1)},${y(v).toFixed(1)} L${((i + 1) * step).toFixed(1)},${y(v).toFixed(1)} `
  })
  return (
    <svg className="sparkline" width={width} height={height} role="img"
         aria-label={`Load over the last hour, peak ${formatAas(Math.max(...values))} active sessions`}>
      <title>{`Last 60 minutes · peak ${formatAas(Math.max(...values))} active sessions`}</title>
      <path d={`${line}L${width},${height} L0,${height} Z`} className="sparkline-area" />
      <path d={line} className="sparkline-line" />
    </svg>
  )
}
