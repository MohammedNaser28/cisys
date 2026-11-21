\newpage

# Reports

**Reports** sum up the builds of several projects. Open them with **Reports** in the header. They are for users with the `lead` or `admin` realm role:

* **Admins** see every project.
* **Leads** see projects where they have effective project `LEAD` access, directly or through a group link. Projects where they are only a `MEMBER`, directly or through a group link, are left out.

## Filters

| Filter  | Values |
| :------ | :----- |
| Project | All projects, or one of them |
| Period  | Last 7 days, last 30 days (default), last year, all time |

A build counts toward the period it was requested in.

## Contents

* **Totals**: builds, passed, failed, cancelled, success rate and average duration.
* **Projects**: the same figures per project.
* **Triggers**: the same figures per trigger (manual, poll, pull request).
* **Branches**: the same figures for the 20 busiest branches, shown only when a single project is selected.
* **Recent failures**: the last 20 failed builds, each linking to its build page.

The success rate is passed / (passed + failed), so cancelled, queued and running builds are left out. The average duration only counts builds that started and finished.

## PDF export

The export button downloads the same report, with the same filters, as a PDF.

## API

| Method | Path | Result |
| :----- | :--- | :----- |
| GET | `/api/reports/builds?projectId=&period=` | The report as JSON |
| GET | `/api/reports/builds/pdf?projectId=&period=` | The report as a PDF |

`period` is `7d`, `30d`, `365d` or `all`. A `projectId` outside the caller's effective project access, direct or through a group link, answers `403`, and so does a caller without the `lead` or `admin` role.
