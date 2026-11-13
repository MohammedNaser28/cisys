/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import {
  FolderGit2,
  Hammer,
  CheckCircle2,
  XCircle,
  Loader,
  Percent,
  CalendarDays,
  Clock,
} from "lucide-react";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
} from "./components/ui/card";

function relativeTime(value) {
  if (!value) return "Never";
  const seconds = Math.round((new Date(value).getTime() - Date.now()) / 1000);
  const units = [
    ["year", 31536000],
    ["month", 2592000],
    ["day", 86400],
    ["hour", 3600],
    ["minute", 60],
  ];
  const format = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
  for (const [unit, size] of units)
    if (Math.abs(seconds) >= size)
      return format.format(Math.round(seconds / size), unit);
  return format.format(seconds, "second");
}

function StatTile({ icon: Icon, label, value }) {
  return (
    <Card className="project-card stat-card">
      <CardHeader>
        <CardDescription className="stat-label">
          <Icon size={16} aria-hidden="true" />
          {label}
        </CardDescription>
        <CardTitle className="stat-value">{value}</CardTitle>
      </CardHeader>
    </Card>
  );
}

/**
 * Statistics for the public projects of the instance, shown on the public front page and the signed-in home page.
 * Presentation only: `stats` is undefined while loading, `statsError` is set when loading failed.
 */
export default function PublicStats({ stats, statsError }) {
  const number = (n) => new Intl.NumberFormat().format(n);
  return (
    <section aria-labelledby="stats-heading">
      <h2 id="stats-heading" className="sr-only">
        Statistics
      </h2>
      {statsError ? (
        <div className="error-message" role="alert">
          {statsError}
        </div>
      ) : stats == null ? (
        <p className="loading-state" role="status">
          Loading statistics…
        </p>
      ) : (
        <div className="stats-grid">
          <StatTile
            icon={FolderGit2}
            label="Public projects"
            value={number(stats.projects)}
          />
          <StatTile icon={Hammer} label="Builds" value={number(stats.builds)} />
          <StatTile
            icon={CheckCircle2}
            label="Passed"
            value={number(stats.passed)}
          />
          <StatTile
            icon={XCircle}
            label="Failed"
            value={number(stats.failed)}
          />
          <StatTile
            icon={Loader}
            label="Running"
            value={number(stats.running)}
          />
          <StatTile
            icon={Percent}
            label="Success rate"
            value={
              stats.successRate == null
                ? "—"
                : `${Math.round(stats.successRate * 100)}%`
            }
          />
          <StatTile
            icon={CalendarDays}
            label="Last 7 days"
            value={number(stats.buildsLast7Days)}
          />
          <StatTile
            icon={Clock}
            label="Last build"
            value={relativeTime(stats.lastBuildAt)}
          />
        </div>
      )}
    </section>
  );
}
