import { LineChart } from 'echarts/charts'
import { BrushComponent, GridComponent, LegendComponent, ToolboxComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { useEffect, useRef } from 'react'
import { formatAas, formatDateTime, formatTime } from '../format'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, BrushComponent, ToolboxComponent, CanvasRenderer])

export interface ChartSeries {
  name: string
  color: string
  values: number[]
}

interface Props {
  times: number[]
  bucketSeconds: number
  series: ChartSeries[]
  dark: boolean
  /** Style name; the chart re-reads its colours when it changes. */
  theme: string
  /** Called with the dragged time range, or null when the selection is cleared. */
  onSelect: (range: [number, number] | null) => void
  /** Change this value to remove the selection rectangle from outside. */
  clearSignal: number
}

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c] as string)
}

/** Stacked area chart of average active sessions. Drag across it to select a time range. */
export function ActivityChart({ times, bucketSeconds, series, dark, theme, onSelect, clearSignal }: Props) {
  const element = useRef<HTMLDivElement>(null)
  const chart = useRef<echarts.ECharts | null>(null)
  const seriesKey = useRef('')
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect

  useEffect(() => {
    const instance = echarts.init(element.current!)
    chart.current = instance
    instance.on('brushEnd', (event) => {
      const areas = (event as { areas?: { coordRange?: [number, number] }[] }).areas ?? []
      const range = areas[0]?.coordRange
      if (range && range[1] - range[0] >= 1000) {
        onSelectRef.current([Math.round(range[0]), Math.round(range[1])])
      } else {
        onSelectRef.current(null)
      }
    })
    const observer = new ResizeObserver(() => instance.resize())
    observer.observe(element.current!)
    return () => {
      observer.disconnect()
      instance.dispose()
      chart.current = null
    }
  }, [])

  useEffect(() => {
    const instance = chart.current
    if (!instance) return
    const text = cssVar('--text-secondary')
    const grid = cssVar('--grid')
    const surface = cssVar('--surface')
    const spanMs = times.length > 1 ? times[times.length - 1] - times[0] : 0
    const multiDay = spanMs > 36 * 3600 * 1000
    // A tooltip left open while the set of series changes would point at series that no longer exist
    const key = series.map((s) => s.name).join('|')
    if (key !== seriesKey.current) {
      seriesKey.current = key
      instance.dispatchAction({ type: 'hideTip' })
    }

    instance.setOption(
      {
        animation: false,
        // The brush registers itself with the toolbox; selection is always on, so no toolbar is shown
        toolbox: { show: false },
        grid: { left: 48, right: 16, top: 16, bottom: 56 },
        legend: {
          type: 'scroll',
          bottom: 0,
          icon: 'roundRect',
          itemWidth: 12,
          itemHeight: 12,
          textStyle: { color: text, fontSize: 12, fontFamily: cssVar('--font') },
          pageTextStyle: { color: text },
          pageIconColor: text,
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'line', lineStyle: { color: text, width: 1 } },
          backgroundColor: surface,
          borderColor: grid,
          textStyle: { color: cssVar('--text-primary'), fontSize: 12, fontFamily: cssVar('--font') },
          formatter: (params: unknown) => {
            const items = (params as { seriesName: string; color: string; value: [number, number] }[])
              .filter((p) => p.value[1] > 0)
              .sort((a, b) => b.value[1] - a.value[1])
            const all = params as { value: [number, number] }[]
            if (all.length === 0) return ''
            const start = all[0].value[0]
            const total = all.reduce((sum, p) => sum + p.value[1], 0)
            const when = `${multiDay ? formatDateTime(start, false) : formatTime(start)} – ${formatTime(start + bucketSeconds * 1000)}`
            const rows = items
              .slice(0, 12)
              .map(
                (p) =>
                  `<div class="tip-row"><span class="tip-swatch" style="background:${p.color}"></span>` +
                  `<span class="tip-name">${escapeHtml(p.seriesName)}</span><b>${formatAas(p.value[1])}</b></div>`,
              )
              .join('')
            return (
              `<div class="tip-title">${when}</div>${rows}` +
              `<div class="tip-row tip-total"><span class="tip-name">Active sessions</span><b>${formatAas(total)}</b></div>`
            )
          },
        },
        xAxis: {
          type: 'time',
          min: times[0],
          max: times.length ? times[times.length - 1] + bucketSeconds * 1000 : undefined,
          axisLine: { lineStyle: { color: grid } },
          axisLabel: { color: text, hideOverlap: true },
          splitLine: { show: false },
        },
        yAxis: {
          type: 'value',
          min: 0,
          minInterval: 0.5,
          axisLabel: { color: text },
          splitLine: { lineStyle: { color: grid } },
        },
        brush: {
          toolbox: [],
          xAxisIndex: 0,
          brushMode: 'single',
          transformable: true,
          throttleType: 'debounce',
          throttleDelay: 150,
          brushStyle: { color: dark ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.10)', borderColor: text, borderWidth: 1 },
          // Keep series colours unchanged outside the selection
          outOfBrush: { colorAlpha: 1 },
        },
        series: series.map((s) => ({
          id: s.name,
          name: s.name,
          type: 'line',
          stack: 'activity',
          step: 'end',
          symbol: 'none',
          color: s.color,
          lineStyle: { width: 1, color: surface },
          areaStyle: { opacity: 1, color: s.color },
          itemStyle: { color: s.color },
          emphasis: { disabled: true },
          data: s.values.map((v, i) => [times[i], v]),
        })),
      },
      { replaceMerge: ['series'] },
    )
    // Dragging on the plot always selects a range; no toolbar button needed
    instance.dispatchAction({
      type: 'takeGlobalCursor',
      key: 'brush',
      brushOption: { brushType: 'lineX', brushMode: 'single' },
    })
  }, [times, bucketSeconds, series, dark, theme])

  useEffect(() => {
    chart.current?.dispatchAction({ type: 'brush', areas: [] })
  }, [clearSignal])

  return <div ref={element} className="activity-chart" role="img" aria-label="Average active sessions over time" />
}
