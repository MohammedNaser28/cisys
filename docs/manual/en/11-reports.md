\newpage

# Reports

**Reports** sum up the builds of several projects. Open them with **Reports** in the header. They are for users with the `lead` or `admin` realm role:

- **Admins** see every project.
- **Leads** see projects where they have effective project `LEAD` access, either directly or through the project's team. Projects where they are only a `MEMBER` are left out.

## Layout

The page has two tabs, and the filters above them apply to both:

- **Overview**: the totals and the summary tables (projects, triggers, branches and recent failures).
- **Trends**: the charts, the duration histogram, the slowest projects and branches, and the flaky revisions.

## Filters

| Filter  | Values                                                   |
| :------ | :------------------------------------------------------- |
| Project | All projects, or one of them                             |
| Period  | Last 7 days, last 30 days (default), last year, all time |

A build counts toward the period it was requested in.

## Contents

- **Totals**: builds, passed, failed, cancelled, success rate and average duration.
- **Projects**: the same figures per project.
- **Triggers**: the same figures per trigger (manual, poll, pull request).
- **Branches**: the same figures for the 20 busiest branches, shown only when a single project is selected.
- **Recent failures**: the last 20 failed builds, each linking to its build page.

The names in every **Project** column, here and on the **Trends** tab, link to the project's home page.

The success rate is passed / (passed + failed), so cancelled, queued and running builds are left out. The average duration only counts builds that started and finished.

## Build trends

The **Trends** tab shows the same figures over time, together with a duration histogram, the slowest projects and branches and the flaky revisions. The charts follow the same project and period filters as the overview.

The charts are drawn by a small built-in SVG component; **Reports does not use a charting library**. They have labelled axes, light gridlines and a hover or keyboard-focus tooltip with the exact values. Build volume, the duration histogram and the per-trigger split are bar charts (the trigger split is stacked); success rate, durations and queue wait are line charts. Each chart has a **Show data** toggle that reveals the same figures as a table, which is also the accessible fallback for the chart.

| Filter   | Values                                                   |
| :------- | :------------------------------------------------------- |
| Project  | All projects, or one of them                             |
| Period   | Last 7 days, last 30 days (default), last year, all time |
| Bucket   | Automatic (default), daily, weekly                       |
| From, To | Optional dates that replace the period                   |

A range whose From date is after its To date, or a date that is not a valid ISO date, is rejected with a clear message; **Clear dates** removes a custom range and returns to the period preset.

Each bucket is an interval of a UTC day or week, keyed by when the build was requested (or started, for very old builds with no request time). Counts are zero for a bucket with no builds, but success rate and duration lines show a gap and **No data** in the tooltip and table when no build measured that figure, rather than pretending it was zero.

For every bucket the trend reports:

- **Builds**, **passed**, **failed** and **cancelled** counts.
- **Success rate**, passed / (passed + failed).
- **Average duration**, plus the **p50** and **p95** duration. These only count builds that started and finished.
- **Average queue wait**, the time between a build being requested and starting.

The charts also show builds split by trigger, a fixed-bucket duration histogram (under 1 minute, 1-5 minutes, 5-15 minutes, 15-60 minutes, 1-6 hours, over 6 hours), the ten slowest projects and branches by average duration, and the flakiest revisions: the same project, branch and commit that failed and later passed, newest first, at most twenty. With no completed builds the histogram says **No completed builds** instead of drawing empty bars.

With daily buckets a range of more than 120 days is widened to weekly buckets, and a range that still needs more than 120 buckets keeps the newest 120 and marks the report as truncated. A range without an explicit `from` starts at the oldest retained build.

Trends only cover builds that retention has not deleted: the **Build retention** settings delete older builds, and a deleted build disappears from every chart. The lines therefore span the retained history, not necessarily the whole period.

## PDF export

The export button downloads the same report, with the same filters, as a PDF. The PDF includes the rendered charts (build volume, success rate, durations, queue wait, the per-trigger split and the duration histogram) as well as the trend tables (volume per bucket, the duration histogram, the slowest projects and branches, and the flaky revisions) alongside the summary.

## API

| Method | Path                                                              | Result                   |
| :----- | :---------------------------------------------------------------- | :----------------------- |
| GET    | `/api/reports/builds?projectId=&period=`                          | The report as JSON       |
| GET    | `/api/reports/builds/trends?projectId=&period=&bucket=&from=&to=` | The build trends as JSON |
| GET    | `/api/reports/builds/pdf?projectId=&period=`                      | The report as a PDF      |

`period` is `7d`, `30d`, `365d` or `all`. `bucket` is `day`, `week` or omitted. `from` and `to` are ISO-8601 instants that replace `period`; `from` must not be after `to`. An invalid `period`, `bucket`, `from` or `to` answers `400`. A `projectId` outside the caller's projects answers `403`, and so does a caller without the `lead` or `admin` role. A project reached through one of the caller's teams counts as one of their projects.
