/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it } from "vitest";
import ProjectPicker from "./ProjectPicker";

// Radix Popover and Checkbox use pointer capture and ResizeObserver, which jsdom
// does not implement.
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

const PROJECTS = [
  { id: 1, name: "alpha" },
  { id: 2, name: "beta" },
  { id: 3, name: "gamma" },
  { id: 4, name: "delta" },
];

function renderPicker({ initial = [], projects = PROJECTS } = {}) {
  const changes = [];
  const Harness = () => {
    const [value, setValue] = useState(initial);
    return (
      <ProjectPicker
        projects={projects}
        value={value}
        idPrefix="pick"
        label="Projects"
        onChange={(next) => {
          changes.push(next);
          setValue(next);
        }}
      />
    );
  };
  render(<Harness />);
  return { changes };
}

function open() {
  fireEvent.click(screen.getByLabelText("Projects"));
}

describe("ProjectPicker", () => {
  it("opens a searchable list of projects", () => {
    renderPicker();
    expect(screen.queryByLabelText("Search projects")).toBeNull();
    open();
    expect(screen.getByLabelText("Search projects")).toBeTruthy();
    for (const project of PROJECTS) {
      expect(screen.getByLabelText(project.name)).toBeTruthy();
    }
    expect(screen.getByText("0 of 4 selected")).toBeTruthy();
  });

  it("filters the list and honours Select all for visible projects only", () => {
    const { changes } = renderPicker();
    open();
    fireEvent.change(screen.getByLabelText("Search projects"), {
      target: { value: "al" },
    });
    expect(screen.queryByLabelText("beta")).toBeNull();
    expect(screen.getByLabelText("alpha")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Select all" }));
    expect(changes.at(-1)).toEqual([1]);
    expect(screen.getByText("1 of 4 selected")).toBeTruthy();
  });

  it("toggles a single project and shows it as a removable chip", () => {
    const { changes } = renderPicker();
    open();
    fireEvent.click(screen.getByLabelText("beta"));
    expect(changes.at(-1)).toEqual([2]);
    expect(screen.getByText("1 project selected")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Remove beta" }));
    expect(changes.at(-1)).toEqual([]);
    expect(screen.getByText("Select projects")).toBeTruthy();
  });

  it("clears every selected project", () => {
    const { changes } = renderPicker({ initial: [1, 2, 3] });
    open();
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(changes.at(-1)).toEqual([]);
    expect(screen.getByText("0 of 4 selected")).toBeTruthy();
  });

  it("closes when Done is pressed", () => {
    renderPicker();
    open();
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByLabelText("Search projects")).toBeNull();
  });

  it("reports an empty state when the search matches nothing", () => {
    renderPicker();
    open();
    fireEvent.change(screen.getByLabelText("Search projects"), {
      target: { value: "zzz" },
    });
    expect(screen.getByText("No projects match.")).toBeTruthy();
  });

  it("summarises a fully selected list", () => {
    renderPicker({ initial: [1, 2, 3, 4] });
    expect(screen.getByText("All 4 projects")).toBeTruthy();
  });

  it("marks selected options in the list", () => {
    renderPicker({ initial: [2] });
    open();
    expect(
      screen
        .getByLabelText("beta")
        .closest(".project-picker-option")
        .getAttribute("data-selected"),
    ).toBe("true");
    expect(
      screen
        .getByLabelText("alpha")
        .closest(".project-picker-option")
        .getAttribute("data-selected"),
    ).toBe("false");
  });
});
