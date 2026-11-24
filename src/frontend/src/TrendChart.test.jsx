/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import TrendChart, {
  bucketLabel,
  bucketShort,
  labelIndices,
  linePoints,
  niceTicks,
} from "./TrendChart";

afterEach(cleanup);

const buckets = [
  { bucket: "2026-11-01T00:00:00Z", builds: 2, successRate: 0.5 },
  { bucket: "2026-11-02T00:00:00Z", builds: 4, successRate: 1 },
];

describe("TrendChart", () => {
  it("formats bucket labels", () => {
    expect(bucketLabel(null)).toBe("—");
    expect(bucketLabel("nonsense")).toBe("nonsense");
    expect(bucketLabel("2026-11-01T00:00:00Z")).toBe("2026-11-01");
    expect(bucketLabel("2026-11-01T00:00:00Z", "week")).toBe(
      "Week of 2026-11-01",
    );
  });

  it("formats short x-axis labels", () => {
    expect(bucketShort(null)).toBe("—");
    expect(bucketShort("nonsense")).toBe("nonsense");
    expect(bucketShort("2026-11-01T00:00:00Z")).toBe("11-01");
  });

  it("thins the x labels but keeps the first and last", () => {
    expect(labelIndices(0)).toEqual([]);
    expect(labelIndices(2)).toEqual([0, 1]);
    const indices = labelIndices(40, 8);
    expect(indices[0]).toBe(0);
    expect(indices[indices.length - 1]).toBe(39);
    expect(indices.length).toBeLessThanOrEqual(9);
  });

  it("scales the line to the tallest value", () => {
    const points = linePoints([0, 4], 4).split(" ");
    expect(points).toHaveLength(2);
    expect(points[0].endsWith(",244.0")).toBe(true); // bottom edge
    expect(points[1].endsWith(",16.0")).toBe(true); // top edge
  });

  it("rounds axis ticks, keeping counts integral", () => {
    expect(niceTicks(0)).toEqual([0]);
    expect(niceTicks(4)).toEqual([0, 1, 2, 3, 4]);
    expect(niceTicks(10, true)).toEqual([0, 2, 4, 6, 8, 10]);
    expect(niceTicks(2, true)).toEqual([0, 1, 2]);
    expect(niceTicks(100).at(-1)).toBe(100);
    for (const tick of niceTicks(51, true)) {
      expect(Number.isInteger(tick)).toBe(true);
    }
    expect(niceTicks(51, true).at(-1)).toBeGreaterThanOrEqual(51);
  });

  it("renders axes, gridlines and a labelled chart", () => {
    const { container } = render(
      <TrendChart
        title="Builds over time"
        buckets={buckets}
        series={[{ label: "Builds", value: (b) => b.builds }]}
      />,
    );
    expect(
      screen.getByRole("img", { name: "Builds over time chart" }),
    ).toBeTruthy();
    expect(
      container.querySelectorAll(".trend-grid").length,
    ).toBeGreaterThanOrEqual(3);
    expect(container.querySelectorAll(".trend-ytick").length).toBe(5);
    // Two buckets, so both x labels survive thinning.
    expect(container.querySelectorAll(".trend-xtick").length).toBe(2);
  });

  it("keeps the data table collapsed until Show data is pressed", () => {
    const { container } = render(
      <TrendChart
        title="Builds over time"
        buckets={buckets}
        series={[{ label: "Builds", value: (b) => b.builds }]}
      />,
    );
    expect(screen.queryByRole("table")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show data" }));
    const table = screen.getByRole("table");
    expect(table.textContent).toContain("2026-11-01");
    expect(table.textContent).toContain("2026-11-02");
    expect(table.textContent).toContain("Builds");
    expect(container.querySelectorAll(".trend-bar-fill").length).toBe(0);
    fireEvent.click(screen.getByRole("button", { name: "Hide data" }));
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("uses the supplied formatter in the table", () => {
    render(
      <TrendChart
        title="Success rate"
        buckets={buckets}
        format={(v) => (v == null ? "—" : `${Math.round(v)}%`)}
        series={[
          {
            label: "Rate",
            value: (b) => (b.successRate == null ? null : b.successRate * 100),
          },
        ]}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Show data" }));
    expect(screen.getByRole("table").textContent).toContain("50%");
    expect(screen.getByRole("table").textContent).toContain("100%");
  });

  it("draws bars in bar mode", () => {
    const { container } = render(
      <TrendChart
        title="Builds over time"
        mode="bar"
        buckets={buckets}
        series={[{ label: "Builds", value: (b) => b.builds }]}
      />,
    );
    expect(container.querySelectorAll(".trend-bar-fill").length).toBe(2);
  });

  it("draws stacked bars for several series", () => {
    const { container } = render(
      <TrendChart
        title="Builds by trigger"
        mode="stacked"
        buckets={buckets}
        series={[
          { label: "Poll", value: (b) => b.builds },
          { label: "Manual", value: () => 1 },
        ]}
      />,
    );
    expect(container.querySelectorAll(".trend-bar-fill").length).toBe(4);
    expect(screen.getByText("Poll")).toBeTruthy();
    expect(screen.getByText("Manual")).toBeTruthy();
  });

  it("shows a tooltip with exact values on hover and focus", () => {
    const { container } = render(
      <TrendChart
        title="Builds over time"
        buckets={buckets}
        series={[{ label: "Builds", value: (b) => b.builds }]}
      />,
    );
    const hits = container.querySelectorAll(".trend-hit");
    expect(hits).toHaveLength(2);
    fireEvent.mouseEnter(hits[1]);
    const tooltip = screen.getByRole("status");
    expect(tooltip.textContent).toContain("2026-11-02");
    expect(tooltip.textContent).toContain("Builds: 4");
    fireEvent.mouseLeave(hits[1]);
    expect(screen.queryByText("Builds: 4")).toBeNull();

    fireEvent.focus(hits[0]);
    expect(screen.getByRole("status").textContent).toContain("Builds: 2");
    fireEvent.blur(hits[0]);
  });

  it("breaks the line where a bucket has no data", () => {
    const gapped = [
      { bucket: "2026-11-01T00:00:00Z", rate: 50 },
      { bucket: "2026-11-02T00:00:00Z", rate: null },
      { bucket: "2026-11-03T00:00:00Z", rate: 100 },
    ];
    const { container } = render(
      <TrendChart
        title="Success rate"
        buckets={gapped}
        series={[{ label: "Rate", value: (b) => b.rate }]}
      />,
    );
    // The null bucket must split the line into two segments, not draw it as 0%.
    expect(container.querySelectorAll(".trend-line")).toHaveLength(2);
    expect(container.querySelectorAll(".trend-point")).toHaveLength(2);
  });

  it("says No data in the tooltip and table for a gap", () => {
    const gapped = [
      { bucket: "2026-11-01T00:00:00Z", rate: 50 },
      { bucket: "2026-11-02T00:00:00Z", rate: null },
    ];
    const { container } = render(
      <TrendChart
        title="Success rate"
        buckets={gapped}
        format={(v) => `${v}%`}
        series={[{ label: "Rate", value: (b) => b.rate }]}
      />,
    );
    fireEvent.mouseEnter(container.querySelectorAll(".trend-hit")[1]);
    expect(screen.getByRole("status").textContent).toContain("No data");
    fireEvent.mouseLeave(container.querySelectorAll(".trend-hit")[1]);
    fireEvent.click(screen.getByRole("button", { name: "Show data" }));
    expect(screen.getByRole("table").textContent).toContain("No data");
  });

  it("shows an empty state without buckets", () => {
    render(<TrendChart title="Builds over time" buckets={[]} series={[]} />);
    expect(screen.getByText("No builds")).toBeTruthy();
    expect(screen.queryByRole("img")).toBeNull();
  });
});
