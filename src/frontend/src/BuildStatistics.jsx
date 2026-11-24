/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import {
  Hammer,
  CheckCircle2,
  XCircle,
  Ban,
  Percent,
  Clock,
  FileDown,
  CalendarX,
} from "lucide-react";
import { Button } from "./components/ui/button";
import { Label } from "./components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./components/ui/select";
import PageHeader from "./components/layout/PageHeader";
import { StatTile } from "./PublicStats";
import TrendChart from "./TrendChart";
import { buildPath, shortRevision, triggerLabel } from "./build-format";
import { formatDateTime } from "./i18n";
import { projectPath } from "./project-paths";

const PERIODS = [
  ["7d", "Last 7 days"],
  ["30d", "Last 30 days"],
  ["365d", "Last year"],
  ["all", "All time"],
];
const BUCKETS = [
  ["", "Automatic"],
  ["day", "Daily"],
  ["week", "Weekly"],
];
const ALL = "all";
const TABS = [
  ["overview", "Overview"],
  ["trends", "Trends"],
];

/** The query string for the report endpoints. */
export function statisticsQuery(projectId, period) {
  const params = new URLSearchParams({ period });
  if (projectId !== ALL) params.set("projectId", projectId);
  return `?${params}`;
}

/** The query string for the trend endpoint, including the optional bucket and range. */
export function trendQuery(projectId, period, bucket, from, to) {
  const params = new URLSearchParams({ period });
  if (projectId !== ALL) params.set("projectId", projectId);
  if (bucket) params.set("bucket", bucket);
  if (from) params.set("from", `${from}T00:00:00Z`);
  if (to) params.set("to", `${to}T23:59:59Z`);
  return `?${params}`;
}

export function percent(rate) {
  return rate == null ? "—" : `${Math.round(rate * 100)}%`;
}

export function seconds(value) {
  if (value == null) return "—";
  const s = Math.round(value),
    m = Math.floor(s / 60);
  return m > 0 ? `${m}m ${s % 60}s` : `${s}s`;
}

const rate = (value) => (value == null ? "—" : `${Math.round(value)}%`);

/** A single cell that keeps tables short but exposes the full value on hover. */
function Truncated({ text }) {
  const value = text || "—";
  return (
    <span className="cell-truncate" title={value}>
      {value}
    </span>
  );
}

/**
 * A project name that links to the project's home page. Builds can outlive their project, which
 * then has no page to link to.
 */
function ProjectLink({ name, projects, onOpenProject }) {
  if (!projects.some((p) => p.name === name)) return <Truncated text={name} />;
  return (
    <a
      className="build-link"
      href={projectPath(name)}
      onClick={(event) => {
        if (!onOpenProject) return;
        event.preventDefault();
        onOpenProject(name);
      }}
    >
      <Truncated text={name} />
    </a>
  );
}

const count = (value) => value ?? "—";

function RowsTable({ title, first, rows, label = (r) => r.label }) {
  return (
    <section className="report-section">
      <h2 className="report-heading">{title}</h2>
      {rows.length === 0 ? (
        <p className="empty-state">No builds</p>
      ) : (
        <div className="builds-table-wrap">
          <table className="builds-table">
            <thead>
              <tr>
                <th scope="col">{first}</th>
                <th scope="col">Builds</th>
                <th scope="col">Passed</th>
                <th scope="col">Failed</th>
                <th scope="col">Cancelled</th>
                <th scope="col">Active</th>
                <th scope="col">Success</th>
                <th scope="col">Avg duration</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.key ?? ""}>
                  <td>{label(r) || "—"}</td>
                  <td>{r.builds}</td>
                  <td>{r.passed}</td>
                  <td>{r.failed}</td>
                  <td>{r.cancelled}</td>
                  <td>{r.active}</td>
                  <td>{percent(r.successRate)}</td>
                  <td>{seconds(r.averageSeconds)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function SlowTable({
  title,
  first,
  rows,
  label = (r) => <Truncated text={r.label} />,
}) {
  return (
    <section className="report-section">
      <h2 className="report-heading">{title}</h2>
      {rows.length === 0 ? (
        <p className="empty-state">No builds</p>
      ) : (
        <div className="builds-table-wrap">
          <table className="builds-table">
            <thead>
              <tr>
                <th scope="col">{first}</th>
                <th scope="col">Builds</th>
                <th scope="col">Avg duration</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.label}>
                  <td>{label(r)}</td>
                  <td>{r.builds}</td>
                  <td>{seconds(r.averageSeconds)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function FlakyTable({ rows, projectLink }) {
  return (
    <section className="report-section">
      <h2 className="report-heading">Flaky revisions</h2>
      {rows.length === 0 ? (
        <p className="empty-state">No flaky revisions</p>
      ) : (
        <div className="builds-table-wrap">
          <table className="builds-table">
            <thead>
              <tr>
                <th scope="col">Project</th>
                <th scope="col">Branch</th>
                <th scope="col">Commit</th>
                <th scope="col">Failed</th>
                <th scope="col">Passed</th>
                <th scope="col">Last</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.projectId}-${r.branch}-${r.revision}`}>
                  <td>{projectLink(r.project)}</td>
                  <td>
                    <Truncated text={r.branch} />
                  </td>
                  <td>
                    <Truncated text={shortRevision(r.revision)} />
                  </td>
                  <td>{r.failures}</td>
                  <td>{r.passes}</td>
                  <td>{r.lastAt ? formatDateTime(r.lastAt) : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function OverviewPanel({ data, loading, onOpenBuild, projectLink }) {
  const totals = data.totals;
  return (
    <>
      {loading && (
        <p className="loading-inline" role="status">
          Refreshing report…
        </p>
      )}
      <div className="stats-grid">
        <StatTile icon={Hammer} label="Builds" value={totals.builds} />
        <StatTile icon={CheckCircle2} label="Passed" value={totals.passed} />
        <StatTile icon={XCircle} label="Failed" value={totals.failed} />
        <StatTile icon={Ban} label="Cancelled" value={totals.cancelled} />
        <StatTile
          icon={Percent}
          label="Success rate"
          value={percent(totals.successRate)}
        />
        <StatTile
          icon={Clock}
          label="Avg duration"
          value={seconds(totals.averageSeconds)}
        />
      </div>
      <RowsTable
        title="Projects"
        first="Project"
        rows={data.byProject}
        label={(r) => projectLink(r.label)}
      />
      <RowsTable
        title="Triggers"
        first="Trigger"
        rows={data.byTrigger}
        label={(r) => triggerLabel(r.key)}
      />
      {data.projectId != null && (
        <RowsTable title="Branches" first="Branch" rows={data.byBranch} />
      )}
      <section className="report-section">
        <h2 className="report-heading">Recent failures</h2>
        {data.recentFailures.length === 0 ? (
          <p className="empty-state">No builds</p>
        ) : (
          <div className="builds-table-wrap">
            <table className="builds-table">
              <thead>
                <tr>
                  <th scope="col">Project</th>
                  <th scope="col">Build</th>
                  <th scope="col">Branch</th>
                  <th scope="col">Commit</th>
                  <th scope="col">Finished</th>
                </tr>
              </thead>
              <tbody>
                {data.recentFailures.map((f) => (
                  <tr key={f.id}>
                    <td>{projectLink(f.project)}</td>
                    <td>
                      <a
                        className="build-link"
                        href={buildPath(f.project, f.branch, f.id)}
                        onClick={(event) => {
                          if (!onOpenBuild) return;
                          event.preventDefault();
                          onOpenBuild(f.project, f.branch || "", f.id);
                        }}
                      >
                        #{f.number}
                      </a>
                    </td>
                    <td>
                      <Truncated text={f.branch} />
                    </td>
                    <td>{shortRevision(f.revision)}</td>
                    <td>{f.finishedAt ? formatDateTime(f.finishedAt) : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

function TrendsPanel({ trends, loading, projectLink }) {
  if (trends == null) {
    return loading ? (
      <p className="loading-state" role="status">
        Loading trends...
      </p>
    ) : null;
  }

  const histogram = (trends.histogram ?? []).map((bucket) => ({
    bucket: bucket.label,
    builds: bucket.builds,
  }));
  const noCompleted = histogram.every((bucket) => bucket.builds === 0);
  return (
    <>
      {loading && (
        <p className="loading-inline" role="status">
          Refreshing trends…
        </p>
      )}
      <TrendChart
        title="Builds over time"
        mode="bar"
        integer
        buckets={trends.buckets}
        granularity={trends.bucket}
        format={count}
        series={[{ label: "Builds", value: (b) => b.builds }]}
      />
      <TrendChart
        title="Success rate"
        buckets={trends.buckets}
        granularity={trends.bucket}
        format={rate}
        yFormat={rate}
        series={[
          {
            label: "Success rate",
            value: (b) => (b.successRate == null ? null : b.successRate * 100),
          },
        ]}
      />
      <TrendChart
        title="Durations"
        buckets={trends.buckets}
        granularity={trends.bucket}
        format={seconds}
        yFormat={seconds}
        series={[
          { label: "Average", value: (b) => b.averageDurationSeconds },
          { label: "P50", value: (b) => b.p50DurationSeconds },
          { label: "P95", value: (b) => b.p95DurationSeconds },
        ]}
      />
      <TrendChart
        title="Queue wait"
        buckets={trends.buckets}
        granularity={trends.bucket}
        format={seconds}
        yFormat={seconds}
        series={[
          { label: "Average queue", value: (b) => b.averageQueueSeconds },
        ]}
      />
      <TrendChart
        title="Builds by trigger"
        mode="stacked"
        integer
        buckets={trends.buckets}
        granularity={trends.bucket}
        format={count}
        series={trends.byTrigger.map((t) => ({
          label: triggerLabel(t.trigger),
          value: (b) =>
            t.buckets.find((x) => x.bucket === b.bucket)?.builds ?? 0,
        }))}
      />
      {noCompleted ? (
        <section className="report-section">
          <h2 className="report-heading">Duration histogram</h2>
          <p className="empty-state">No completed builds</p>
        </section>
      ) : (
        <TrendChart
          title="Duration histogram"
          mode="bar"
          integer
          buckets={histogram}
          format={count}
          series={[{ label: "Builds", value: (b) => b.builds }]}
        />
      )}
      <SlowTable
        title="Slowest projects"
        first="Project"
        rows={trends.slowestProjects}
        label={(r) => projectLink(r.label)}
      />
      <SlowTable
        title="Slowest branches"
        first="Branch"
        rows={trends.slowestBranches}
      />
      <FlakyTable rows={trends.flakyRevisions} projectLink={projectLink} />
    </>
  );
}

/** Build statistics across the projects the caller may report on, with a PDF export. */
export default function BuildStatistics({ load, onOpenBuild, onOpenProject }) {
  const [projectId, setProjectId] = useState(ALL);
  const [period, setPeriod] = useState("30d");
  const [bucket, setBucket] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [tab, setTab] = useState("overview");
  const [data, setData] = useState(null);
  const [statsLoadedKey, setStatsLoadedKey] = useState(null);
  const [error, setError] = useState("");
  const [trends, setTrends] = useState(null);
  const [trendsLoadedKey, setTrendsLoadedKey] = useState(null);
  const [trendError, setTrendError] = useState("");

  const statsKey = statisticsQuery(projectId, period);
  const trendsKey = trendQuery(projectId, period, bucket, from, to);
  // Loading is derived: a refetch is in flight while the last settled request
  // key lags the current filters, so the previous data stays on screen.
  const statsLoading = data != null && statsLoadedKey !== statsKey;
  const trendsLoading = trends != null && trendsLoadedKey !== trendsKey;

  useEffect(() => {
    let current = true;
    load(`/reports/builds${statsKey}`)
      .then((d) => {
        if (!current) return;
        setError("");
        setData(d);
        setStatsLoadedKey(statsKey);
      })
      .catch((e) => {
        if (!current) return;
        setError(e.message);
        setStatsLoadedKey(statsKey);
      });
    return () => {
      current = false;
    };
  }, [load, statsKey]);

  useEffect(() => {
    let current = true;
    load(`/reports/builds/trends${trendsKey}`)
      .then((d) => {
        if (!current) return;
        setTrendError("");
        setTrends(d);
        setTrendsLoadedKey(trendsKey);
      })
      .catch((e) => {
        if (!current) return;
        setTrendError(e.message);
        setTrendsLoadedKey(trendsKey);
      });
    return () => {
      current = false;
    };
  }, [load, trendsKey]);

  async function exportPdf() {
    setError("");
    try {
      const response = await load(
        `/reports/builds/pdf${statisticsQuery(projectId, period)}`,
        { raw: true, headers: { Accept: "application/pdf" } },
      );
      const disposition = response.headers.get("Content-Disposition") || "";
      const name =
        /filename="([^"]+)"/.exec(disposition)?.[1] || "build-report.pdf";
      const url = URL.createObjectURL(await response.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = name;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) {
      setError(e.message);
    }
  }

  function clearDates() {
    setFrom("");
    setTo("");
  }

  function onTabKeyDown(event) {
    if (event.key !== "ArrowRight" && event.key !== "ArrowLeft") return;
    event.preventDefault();
    const order = TABS.map(([id]) => id);
    const delta = event.key === "ArrowRight" ? 1 : -1;
    const next =
      order[(order.indexOf(tab) + delta + order.length) % order.length];
    setTab(next);
    document.getElementById(`report-tab-${next}`)?.focus();
  }

  const projectLink = (name) => (
    <ProjectLink
      name={name}
      projects={data?.projects ?? []}
      onOpenProject={onOpenProject}
    />
  );

  return (
    <main id="reports" className="page-container">
      <PageHeader
        title="Reports"
        actions={
          <Button
            type="button"
            size="icon"
            onClick={exportPdf}
            disabled={!data}
            title="Export PDF"
            aria-label="Export PDF"
          >
            <FileDown size={16} aria-hidden="true" />
          </Button>
        }
      />
      <div className="report-filters">
        <div className="form-field">
          <Label htmlFor="report-project">Project</Label>
          <Select value={String(projectId)} onValueChange={setProjectId}>
            <SelectTrigger id="report-project" className="h-10 w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>All projects</SelectItem>
              {(data?.projects ?? []).map((p) => (
                <SelectItem key={p.id} value={String(p.id)}>
                  {p.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="form-field">
          <Label htmlFor="report-period">Period</Label>
          <Select value={period} onValueChange={setPeriod}>
            <SelectTrigger id="report-period" className="h-10 w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PERIODS.map(([value, label]) => (
                <SelectItem key={value} value={value}>
                  {label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="form-field">
          <Label htmlFor="report-bucket">Bucket</Label>
          <Select
            value={bucket || "auto"}
            onValueChange={(value) => setBucket(value === "auto" ? "" : value)}
          >
            <SelectTrigger id="report-bucket" className="h-10 w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {BUCKETS.map(([value, label]) => (
                <SelectItem key={value || "auto"} value={value || "auto"}>
                  {label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="form-field">
          <Label htmlFor="report-from">From</Label>
          <input
            id="report-from"
            type="date"
            className="report-date"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
        </div>
        <div className="form-field">
          <Label htmlFor="report-to">To</Label>
          <input
            id="report-to"
            type="date"
            className="report-date"
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
        </div>
      </div>
      {(from || to) && (
        <div className="report-date-actions">
          <Button
            type="button"
            variant="outline"
            size="icon"
            title="Clear dates"
            aria-label="Clear dates"
            onClick={clearDates}
          >
            <CalendarX size={16} aria-hidden="true" />
          </Button>
        </div>
      )}
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      {trendError && (
        <div className="error-message" role="alert">
          {trendError}
        </div>
      )}
      {!data ? (
        !error && (
          <p className="loading-state" role="status">
            Loading reports...
          </p>
        )
      ) : (
        <>
          <div
            className="report-tabs"
            role="tablist"
            aria-label="Report sections"
          >
            {TABS.map(([id, label]) => (
              <button
                key={id}
                type="button"
                role="tab"
                id={`report-tab-${id}`}
                className="report-tab"
                aria-selected={tab === id}
                aria-controls={`report-panel-${id}`}
                tabIndex={tab === id ? 0 : -1}
                onClick={() => setTab(id)}
                onKeyDown={onTabKeyDown}
              >
                {label}
              </button>
            ))}
          </div>
          <div
            role="tabpanel"
            id="report-panel-overview"
            aria-labelledby="report-tab-overview"
            hidden={tab !== "overview"}
          >
            <OverviewPanel
              data={data}
              loading={statsLoading}
              onOpenBuild={onOpenBuild}
              projectLink={projectLink}
            />
          </div>
          <div
            role="tabpanel"
            id="report-panel-trends"
            aria-labelledby="report-tab-trends"
            hidden={tab !== "trends"}
          >
            <TrendsPanel
              trends={trends}
              loading={trends == null || trendsLoading}
              projectLink={projectLink}
            />
          </div>
        </>
      )}
    </main>
  );
}
