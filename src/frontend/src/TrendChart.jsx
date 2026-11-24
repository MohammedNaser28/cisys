/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useId, useState } from "react";

const WIDTH = 900;
const HEIGHT = 280;
const PAD_LEFT = 56;
const PAD_RIGHT = 24;
const PAD_TOP = 16;
const PAD_BOTTOM = 36;
const PLOT_W = WIDTH - PAD_LEFT - PAD_RIGHT;
const PLOT_H = HEIGHT - PAD_TOP - PAD_BOTTOM;
const SERIES_COUNT = 6;

/** A short UTC date for a bucket instant, falling back to the raw value. */
export function bucketLabel(value, granularity = "day") {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const text = date.toISOString().slice(0, 10);
  return granularity === "week" ? `Week of ${text}` : text;
}

/** A compact "MM-DD" x-axis label, or the raw value for non-date buckets. */
export function bucketShort(value) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toISOString().slice(5, 10);
}

/**
 * The indices of x labels to draw: at most `maxLabels` evenly spread, always
 * including the first and last bucket, so labels never overlap.
 */
export function labelIndices(count, maxLabels = 8) {
  if (count <= 0) return [];
  const step = Math.max(1, Math.ceil(count / maxLabels));
  const indices = [];
  for (let index = 0; index < count; index += step) indices.push(index);
  if (indices[indices.length - 1] !== count - 1) indices.push(count - 1);
  return indices;
}

/**
 * The polyline segments for one series over a shared value scale. A null value
 * breaks the series into separate segments so a gap is never drawn as zero.
 */
export function lineSegments(values, max) {
  const step = values.length > 1 ? PLOT_W / (values.length - 1) : 0;
  const segments = [];
  let current = [];
  values.forEach((value, i) => {
    if (value == null) {
      if (current.length > 0) segments.push(current.join(" "));
      current = [];
      return;
    }
    const x = PAD_LEFT + i * step;
    const y = PAD_TOP + PLOT_H - (max > 0 ? (value / max) * PLOT_H : 0);
    current.push(`${x.toFixed(1)},${y.toFixed(1)}`);
  });
  if (current.length > 0) segments.push(current.join(" "));
  return segments;
}

/** The polyline points for one series over a shared value scale. */
export function linePoints(values, max) {
  return lineSegments(values, max).join(" ");
}

/**
 * Evenly spaced, rounded axis ticks from zero up to a step boundary at or above
 * `max`. Counts pass `integer` so a small range never produces "2.5 builds"
 * ticks; other values keep fractional steps.
 */
export function niceTicks(max, integer = false, intervals = 5) {
  if (!Number.isFinite(max) || max <= 0) return [0];
  const rough = max / intervals;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const residual = rough / magnitude;
  let step =
    (residual <= 1 ? 1 : residual <= 2 ? 2 : residual <= 5 ? 5 : 10) *
    magnitude;
  if (integer) step = Math.max(1, Math.round(step));
  const top = Math.ceil(max / step) * step;
  const count = Math.round(top / step);
  return Array.from({ length: count + 1 }, (_, i) =>
    Number((i * step).toFixed(10)),
  );
}

function tickLabel(value) {
  if (!Number.isFinite(value)) return "—";
  if (Math.abs(value) >= 100) return String(Math.round(value));
  if (Number.isInteger(value)) return String(value);
  return (Math.round(value * 10) / 10).toString();
}

/** The series values as finite numbers, with `null` kept as a data gap. */
function buildDrawable(rows, series) {
  return series.map((entry) => ({
    label: entry.label,
    values: rows.map((bucket) => {
      const value = entry.value(bucket);
      return typeof value === "number" && Number.isFinite(value) ? value : null;
    }),
  }));
}

/**
 * A dependency-free SVG chart with a collapsible table fallback. It draws a
 * line, grouped bars or stacked bars over labelled axes with light gridlines
 * and a hover/focus tooltip. `series` is a list of `{ label, value }` where
 * `value` maps one bucket to a number or null; `format` renders a value.
 *
 * The SVG carries the visual trend (marked `role="img"` with a text summary)
 * while the table carries the same data for assistive technology and narrow
 * screens, collapsed behind a "Show data" toggle by default.
 */
export default function TrendChart({
  title,
  buckets,
  series,
  format = (value) => value,
  yFormat,
  granularity = "day",
  mode = "line",
  integer = false,
  emptyLabel = "No builds",
}) {
  const [active, setActive] = useState(null);
  const [open, setOpen] = useState(false);
  const summaryId = useId();
  const tableId = useId();
  const rows = buckets ?? [];
  const drawable = buildDrawable(rows, series);
  const empty = rows.length === 0 || series.length === 0;

  const stacked = mode === "stacked";
  const rawMax = stacked
    ? Math.max(
        0,
        ...rows.map((_, index) =>
          drawable.reduce((sum, entry) => sum + (entry.values[index] ?? 0), 0),
        ),
      )
    : Math.max(
        0,
        ...drawable.flatMap((entry) => entry.values).filter((v) => v != null),
      );
  const ticks = niceTicks(rawMax, integer);
  const max = ticks[ticks.length - 1] > 0 ? ticks[ticks.length - 1] : 1;

  const bandW = rows.length > 0 ? PLOT_W / rows.length : PLOT_W;
  const xAt = (index) =>
    mode === "bar" || stacked
      ? PAD_LEFT + bandW * (index + 0.5)
      : rows.length > 1
        ? PAD_LEFT + (PLOT_W * index) / (rows.length - 1)
        : PAD_LEFT + PLOT_W / 2;
  const yAt = (value) =>
    PAD_TOP + PLOT_H - (max > 0 ? (value / max) * PLOT_H : 0);

  const renderTick = yFormat || tickLabel;
  const shownLabels = labelIndices(rows.length);
  const seriesClass = (index) => `trend-series-${index % SERIES_COUNT}`;
  // A null value means "nothing happened in this bucket", which is not zero.
  const display = (value) => (value == null ? "No data" : format(value));

  if (empty) {
    return (
      <section className="report-section">
        <h2 className="report-heading">{title}</h2>
        <p className="empty-state">{emptyLabel}</p>
      </section>
    );
  }

  const first = rows[0];
  const last = rows[rows.length - 1];
  const summary = `${title}: ${rows.length} bucket${
    rows.length === 1 ? "" : "s"
  } from ${bucketLabel(first.bucket, granularity)} to ${bucketLabel(
    last.bucket,
    granularity,
  )}.`;

  const activeRow = active != null && rows[active] ? rows[active] : null;

  return (
    <section className="report-section">
      <div className="report-heading-row">
        <h2 className="report-heading">{title}</h2>
        <button
          type="button"
          className="report-data-toggle"
          aria-expanded={open}
          aria-controls={tableId}
          onClick={() => setOpen((value) => !value)}
        >
          {open ? "Hide data" : "Show data"}
        </button>
      </div>
      <p id={summaryId} className="visually-hidden">
        {summary}
      </p>
      <div className="trend-chart-wrap">
        <div className="trend-chart-canvas">
          <svg
            className="trend-chart"
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            role="img"
            aria-label={`${title} chart`}
            aria-describedby={summaryId}
          >
            {ticks.map((tick) => (
              <g key={`tick-${tick}`}>
                <line
                  className="trend-grid"
                  x1={PAD_LEFT}
                  x2={PAD_LEFT + PLOT_W}
                  y1={yAt(tick)}
                  y2={yAt(tick)}
                />
                <text
                  className="trend-tick trend-ytick"
                  x={PAD_LEFT - 8}
                  y={yAt(tick) + 4}
                  textAnchor="end"
                >
                  {renderTick(tick)}
                </text>
              </g>
            ))}
            <line
              className="trend-axis"
              x1={PAD_LEFT}
              y1={PAD_TOP}
              x2={PAD_LEFT}
              y2={PAD_TOP + PLOT_H}
            />
            <line
              className="trend-axis"
              x1={PAD_LEFT}
              y1={PAD_TOP + PLOT_H}
              x2={PAD_LEFT + PLOT_W}
              y2={PAD_TOP + PLOT_H}
            />
            {mode === "line" &&
              drawable.map((entry, seriesIndex) => (
                <g key={entry.label} className={seriesClass(seriesIndex)}>
                  {lineSegments(entry.values, max).map((points, segment) => (
                    <polyline
                      key={segment}
                      className="trend-line"
                      points={points}
                    />
                  ))}
                  {rows.length <= 60 &&
                    entry.values.map((value, index) =>
                      value == null ? null : (
                        <circle
                          key={index}
                          className="trend-point"
                          cx={xAt(index)}
                          cy={yAt(value)}
                          r={2.5}
                        />
                      ),
                    )}
                </g>
              ))}
            {mode === "bar" &&
              rows.map((bucket, index) => {
                const groupW = bandW * 0.72;
                const barW = groupW / Math.max(1, drawable.length);
                return drawable.map((entry, seriesIndex) => {
                  const value = entry.values[index];
                  if (value == null) return null;
                  const height = (value / max) * PLOT_H;
                  return (
                    <rect
                      key={`${seriesIndex}-${bucket.bucket}`}
                      className={`trend-bar-fill ${seriesClass(seriesIndex)}`}
                      x={
                        xAt(index) -
                        groupW / 2 +
                        seriesIndex * barW +
                        barW * 0.1
                      }
                      y={PAD_TOP + PLOT_H - height}
                      width={Math.max(1, barW * 0.8)}
                      height={height}
                    />
                  );
                });
              })}
            {stacked &&
              rows.map((bucket, index) => {
                const groupW = bandW * 0.72;
                const x = xAt(index) - groupW / 2;
                let stack = 0;
                return drawable.map((entry, seriesIndex) => {
                  const value = entry.values[index];
                  if (value == null) return null;
                  const height = (value / max) * PLOT_H;
                  const y = PAD_TOP + PLOT_H - stack - height;
                  stack += height;
                  return (
                    <rect
                      key={`${seriesIndex}-${bucket.bucket}`}
                      className={`trend-bar-fill ${seriesClass(seriesIndex)}`}
                      x={x}
                      y={y}
                      width={groupW}
                      height={height}
                    />
                  );
                });
              })}
            {shownLabels.map((index) => (
              <text
                key={`x-${index}`}
                className="trend-tick trend-xtick"
                x={xAt(index)}
                y={HEIGHT - 12}
                textAnchor="middle"
              >
                {bucketShort(rows[index].bucket)}
              </text>
            ))}
            {rows.map((bucket, index) => {
              const label =
                `${bucketLabel(bucket.bucket, granularity)}: ` +
                series
                  .map(
                    (entry) => `${entry.label} ${display(entry.value(bucket))}`,
                  )
                  .join(", ");
              return (
                <rect
                  key={`hit-${bucket.bucket}`}
                  className="trend-hit"
                  x={xAt(index) - bandW / 2}
                  y={PAD_TOP}
                  width={Math.max(bandW, 1)}
                  height={PLOT_H}
                  tabIndex={0}
                  role="button"
                  aria-label={label}
                  onMouseEnter={() => setActive(index)}
                  onFocus={() => setActive(index)}
                  onMouseLeave={() => setActive(null)}
                  onBlur={() => setActive(null)}
                />
              );
            })}
          </svg>
          {activeRow && (
            <div
              className="trend-tooltip"
              role="status"
              style={{
                left: `clamp(8rem, ${(xAt(active) / WIDTH) * 100}%, calc(100% - 8rem))`,
              }}
            >
              <strong>{bucketLabel(activeRow.bucket, granularity)}</strong>
              <ul>
                {series.map((entry, seriesIndex) => (
                  <li key={entry.label}>
                    <span
                      className={`trend-swatch ${seriesClass(seriesIndex)}`}
                      aria-hidden="true"
                    />
                    {entry.label}: {display(entry.value(activeRow))}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>
      <ul className="trend-legend">
        {series.map((entry, seriesIndex) => (
          <li key={entry.label}>
            <span
              className={`trend-swatch ${seriesClass(seriesIndex)}`}
              aria-hidden="true"
            />
            {entry.label}
          </li>
        ))}
      </ul>
      {open && (
        <div className="builds-table-wrap" id={tableId}>
          <table className="builds-table">
            <thead>
              <tr>
                <th scope="col">Bucket</th>
                {series.map((entry) => (
                  <th key={entry.label} scope="col">
                    {entry.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((bucket) => (
                <tr key={bucket.bucket}>
                  <td>{bucketLabel(bucket.bucket, granularity)}</td>
                  {series.map((entry) => (
                    <td key={entry.label}>{display(entry.value(bucket))}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
