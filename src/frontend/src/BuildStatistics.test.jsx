/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import BuildStatistics, {
  percent,
  seconds,
  statisticsQuery,
  trendQuery,
} from "./BuildStatistics";

// Radix Select uses pointer capture and ResizeObserver, which jsdom does not implement.
beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
  Element.prototype.scrollIntoView = () => {};
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

afterEach(cleanup);

const row = (key, label, extra = {}) => ({
  key,
  label,
  builds: 4,
  passed: 2,
  failed: 1,
  cancelled: 1,
  active: 0,
  successRate: 2 / 3,
  averageSeconds: 95,
  lastBuildAt: null,
  ...extra,
});

const statistics = {
  projects: [
    { id: 1, name: "alpha" },
    { id: 2, name: "beta" },
  ],
  projectId: null,
  period: "30d",
  totals: row(null, ""),
  byProject: [row("1", "alpha")],
  byTrigger: [row("POLL", "POLL")],
  byBranch: [],
  recentFailures: [
    {
      id: 9,
      projectId: 1,
      project: "alpha",
      number: 3,
      branch: "main",
      revision: "0123456789",
      finishedAt: "2026-11-20T10:00:00Z",
    },
  ],
};

const point = (bucket, extra = {}) => ({
  bucket,
  builds: 2,
  passed: 1,
  failed: 1,
  cancelled: 0,
  active: 0,
  successRate: 0.5,
  averageDurationSeconds: 30,
  p50DurationSeconds: 20,
  p95DurationSeconds: 60,
  averageQueueSeconds: 5,
  ...extra,
});

const trends = {
  projects: statistics.projects,
  projectId: null,
  period: "30d",
  bucket: "day",
  truncated: false,
  from: "2026-11-01T00:00:00Z",
  to: "2026-11-02T00:00:00Z",
  generatedAt: "2026-11-02T12:00:00Z",
  totals: point(null),
  buckets: [
    point("2026-11-01T00:00:00Z"),
    point("2026-11-02T00:00:00Z", { builds: 3, successRate: 1 }),
  ],
  byTrigger: [
    { trigger: "POLL", buckets: [point("2026-11-01T00:00:00Z")] },
    { trigger: "MANUAL", buckets: [point("2026-11-02T00:00:00Z")] },
  ],
  histogram: [
    { label: "< 1m", builds: 1 },
    { label: "1-5m", builds: 2 },
  ],
  slowestProjects: [{ label: "alpha", builds: 2, averageSeconds: 30 }],
  slowestBranches: [{ label: "main", builds: 2, averageSeconds: 30 }],
  flakyRevisions: [
    {
      projectId: 1,
      project: "alpha",
      branch: "main",
      revision: "0123456789",
      failures: 1,
      passes: 1,
      lastAt: "2026-11-02T00:00:00Z",
    },
  ],
};

function loader({ stats = statistics, trend = trends, pdf } = {}) {
  return vi.fn((path) => {
    if (path.includes("/pdf")) return pdf(path);
    if (path.includes("/trends")) return Promise.resolve(trend);
    return Promise.resolve(stats);
  });
}

const trendsCalls = (load) =>
  load.mock.calls.filter(([path]) => path.startsWith("/reports/builds/trends"));
const statsCalls = (load) =>
  load.mock.calls.filter(
    ([path]) =>
      path.startsWith("/reports/builds?") ||
      path.startsWith("/reports/builds/pdf"),
  );

function choose(label, option) {
  const trigger = screen.getByLabelText(label);
  fireEvent.pointerDown(trigger, { button: 0, pointerType: "mouse" });
  fireEvent.pointerUp(trigger, { button: 0, pointerType: "mouse" });
  return screen
    .findByRole("option", { name: option })
    .then((o) => fireEvent.click(o));
}

const openTab = (name) => fireEvent.click(screen.getByRole("tab", { name }));

describe("BuildStatistics", () => {
  it("formats values and queries", () => {
    expect(percent(null)).toBe("—");
    expect(percent(2 / 3)).toBe("67%");
    expect(seconds(null)).toBe("—");
    expect(seconds(42)).toBe("42s");
    expect(seconds(95)).toBe("1m 35s");
    expect(statisticsQuery("all", "30d")).toBe("?period=30d");
    expect(statisticsQuery("2", "all")).toBe("?period=all&projectId=2");
    expect(trendQuery("all", "30d", "", "", "")).toBe("?period=30d");
    const full = new URLSearchParams(
      trendQuery("2", "all", "week", "2026-11-01", "2026-11-10").slice(1),
    );
    expect(full.get("projectId")).toBe("2");
    expect(full.get("period")).toBe("all");
    expect(full.get("bucket")).toBe("week");
    expect(full.get("from")).toBe("2026-11-01T00:00:00Z");
    expect(full.get("to")).toBe("2026-11-10T23:59:59Z");
  });

  it("shows the overview by default and the trends on the second tab", async () => {
    const load = loader();
    const onOpenBuild = vi.fn();
    render(<BuildStatistics load={load} onOpenBuild={onOpenBuild} />);

    expect(await screen.findAllByText("Poll")).toBeTruthy();
    expect(statsCalls(load)[0][0]).toBe("/reports/builds?period=30d");
    expect(trendsCalls(load)[0][0]).toBe("/reports/builds/trends?period=30d");
    expect(screen.getAllByText("67%").length).toBeGreaterThan(0);
    expect(screen.getAllByText("0123456").length).toBeGreaterThan(0);
    expect(screen.queryByText("Branches")).toBeNull();
    expect(
      screen
        .getByRole("tab", { name: "Overview" })
        .getAttribute("aria-selected"),
    ).toBe("true");

    fireEvent.click(screen.getByText("#3"));
    expect(onOpenBuild).toHaveBeenCalledWith("alpha", "main", 9);

    openTab("Trends");
    expect(
      screen.getByRole("tab", { name: "Trends" }).getAttribute("aria-selected"),
    ).toBe("true");
    expect(
      screen.getByRole("img", { name: "Builds over time chart" }),
    ).toBeTruthy();
    expect(screen.getByText("Duration histogram")).toBeTruthy();
    expect(screen.getByText("Slowest projects")).toBeTruthy();
    expect(screen.getByText("Slowest branches")).toBeTruthy();
    expect(screen.getByText("Flaky revisions")).toBeTruthy();

    await choose("Project", "beta");
    await waitFor(() => {
      expect(statsCalls(load).at(-1)[0]).toBe(
        "/reports/builds?period=30d&projectId=2",
      );
      expect(trendsCalls(load).at(-1)[0]).toBe(
        "/reports/builds/trends?period=30d&projectId=2",
      );
    });
    await choose("Period", "All time");
    await waitFor(() =>
      expect(trendsCalls(load).at(-1)[0]).toBe(
        "/reports/builds/trends?period=all&projectId=2",
      ),
    );
    await choose("Bucket", "Weekly");
    await waitFor(() =>
      expect(trendsCalls(load).at(-1)[0]).toBe(
        "/reports/builds/trends?period=all&projectId=2&bucket=week",
      ),
    );
  });

  it("links every Project column to the project home pages", async () => {
    const stats = {
      ...statistics,
      byProject: [row("1", "alpha"), row("99", "99")],
    };
    const onOpenProject = vi.fn();
    render(
      <BuildStatistics
        load={loader({ stats })}
        onOpenProject={onOpenProject}
      />,
    );
    await screen.findAllByText("Poll");
    await screen.findByText("Flaky revisions");

    // Projects, recent failures, slowest projects and flaky revisions.
    const links = screen.getAllByRole("link", { name: "alpha", hidden: true });
    expect(links).toHaveLength(4);
    links.forEach((link) => {
      expect(link.getAttribute("href")).toBe("/alpha/");
      fireEvent.click(link);
    });
    expect(onOpenProject.mock.calls).toEqual(Array(4).fill(["alpha"]));
    // A project that no longer exists has no page.
    expect(screen.getByText("99")).toBeTruthy();
    expect(screen.queryByRole("link", { name: "99" })).toBeNull();
  });

  it("switches tabs from the keyboard", async () => {
    render(<BuildStatistics load={loader()} />);
    await screen.findAllByText("Poll");
    fireEvent.keyDown(screen.getByRole("tab", { name: "Overview" }), {
      key: "ArrowRight",
    });
    expect(
      screen.getByRole("tab", { name: "Trends" }).getAttribute("aria-selected"),
    ).toBe("true");
    fireEvent.keyDown(screen.getByRole("tab", { name: "Trends" }), {
      key: "ArrowLeft",
    });
    expect(
      screen
        .getByRole("tab", { name: "Overview" })
        .getAttribute("aria-selected"),
    ).toBe("true");
  });

  it("keeps the previous trends while refetching", async () => {
    let resolveSecond;
    let trendCalls = 0;
    const load = vi.fn((path) => {
      if (path.includes("/trends")) {
        trendCalls += 1;
        return trendCalls === 1
          ? Promise.resolve(trends)
          : new Promise((resolve) => {
              resolveSecond = resolve;
            });
      }
      return Promise.resolve(statistics);
    });
    render(<BuildStatistics load={load} />);
    await screen.findAllByText("Poll");
    openTab("Trends");
    expect(
      await screen.findByRole("img", { name: "Builds over time chart" }),
    ).toBeTruthy();

    await choose("Period", "All time");
    await waitFor(() => expect(trendCalls).toBe(2));
    // The old chart stays mounted while the new request is in flight.
    expect(
      screen.getByRole("img", { name: "Builds over time chart" }),
    ).toBeTruthy();
    expect(screen.getByText("Refreshing trends…")).toBeTruthy();

    resolveSecond({
      ...trends,
      period: "all",
      buckets: [point("2026-10-01T00:00:00Z")],
    });
    await waitFor(() =>
      expect(screen.queryByText("Refreshing trends…")).toBeNull(),
    );
  });

  it("re-issues the trend request when the date range changes", async () => {
    const load = loader();
    render(<BuildStatistics load={load} />);
    await screen.findAllByText("Poll");
    fireEvent.change(screen.getByLabelText("From"), {
      target: { value: "2026-10-01" },
    });
    fireEvent.change(screen.getByLabelText("To"), {
      target: { value: "2026-11-01" },
    });
    await waitFor(() => {
      const query = new URLSearchParams(
        trendsCalls(load).at(-1)[0].split("?")[1],
      );
      expect(query.get("from")).toBe("2026-10-01T00:00:00Z");
      expect(query.get("to")).toBe("2026-11-01T23:59:59Z");
    });
  });

  it("shows a trend range error above the tabs", async () => {
    const load = vi.fn((path) =>
      path.includes("/trends")
        ? Promise.reject(new Error("From must not be after To."))
        : Promise.resolve(statistics),
    );
    render(<BuildStatistics load={load} />);
    await screen.findAllByText("Poll");
    // The alert must be reachable even while the Overview tab is showing.
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("From must not be after To.");
    expect(
      screen
        .getByRole("tab", { name: "Overview" })
        .getAttribute("aria-selected"),
    ).toBe("true");
  });

  it("clears custom dates", async () => {
    const load = loader();
    render(<BuildStatistics load={load} />);
    await screen.findAllByText("Poll");
    fireEvent.change(screen.getByLabelText("From"), {
      target: { value: "2026-10-01" },
    });
    fireEvent.change(screen.getByLabelText("To"), {
      target: { value: "2026-11-01" },
    });
    await waitFor(() =>
      expect(trendsCalls(load).at(-1)[0]).toContain("from=2026-10-01"),
    );
    fireEvent.click(screen.getByRole("button", { name: "Clear dates" }));
    expect(screen.getByLabelText("From").value).toBe("");
    expect(screen.getByLabelText("To").value).toBe("");
    await waitFor(() => {
      const query = trendsCalls(load).at(-1)[0];
      expect(query).not.toContain("from=");
      expect(query).not.toContain("to=");
    });
    expect(screen.queryByRole("button", { name: "Clear dates" })).toBeNull();
  });

  it("shows No completed builds for an empty histogram", async () => {
    const emptyHistogram = {
      ...trends,
      histogram: trends.histogram.map((h) => ({ ...h, builds: 0 })),
    };
    render(<BuildStatistics load={loader({ trend: emptyHistogram })} />);
    await screen.findAllByText("Poll");
    openTab("Trends");
    expect(await screen.findByText("No completed builds")).toBeTruthy();
    expect(
      screen.queryByRole("img", { name: "Duration histogram chart" }),
    ).toBeNull();
  });

  it("shows a trends error without hiding the report", async () => {
    const load = vi.fn((path) =>
      path.includes("/trends")
        ? Promise.reject(new Error("trends failed"))
        : Promise.resolve(statistics),
    );
    render(<BuildStatistics load={load} />);
    await screen.findAllByText("Poll");
    openTab("Trends");
    expect((await screen.findByText("trends failed")).textContent).toBe(
      "trends failed",
    );
    openTab("Overview");
    expect(screen.getByText("Recent failures")).toBeTruthy();
  });

  it("downloads the PDF", async () => {
    const blob = new Blob(["%PDF"], { type: "application/pdf" });
    const load = loader({
      pdf: () =>
        Promise.resolve({
          headers: new Headers({
            "Content-Disposition":
              'attachment; filename="build-report-2026-11-21.pdf"',
          }),
          blob: () => Promise.resolve(blob),
        }),
    });
    URL.createObjectURL = vi.fn(() => "blob:report");
    URL.revokeObjectURL = vi.fn();
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    render(<BuildStatistics load={load} />);
    await screen.findAllByText("Poll");

    fireEvent.click(screen.getByLabelText("Export PDF"));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(statsCalls(load).at(-1)[0]).toBe("/reports/builds/pdf?period=30d");
    expect(URL.createObjectURL).toHaveBeenCalledWith(blob);
    click.mockRestore();
  });

  it("shows a load error", async () => {
    const load = vi.fn().mockRejectedValue(new Error("Forbidden"));
    render(<BuildStatistics load={load} />);
    const alerts = await screen.findAllByRole("alert");
    expect(alerts.map((a) => a.textContent)).toContain("Forbidden");
  });
});
