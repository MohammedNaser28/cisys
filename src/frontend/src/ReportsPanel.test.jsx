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
import ReportsPanel from "./ReportsPanel";

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

const ORANGU = "ai.mnemosyne.cisys.OranguReport";
const OVERVIEW = {
  plugins: [
    { type: "orangu", className: ORANGU, variables: "path=/opt/orangu" },
  ],
  classes: ["ai.mnemosyne.cisys.ListReport", ORANGU],
};

describe("ReportsPanel", () => {
  it("lists the configured report types", async () => {
    const api = vi.fn(async () => OVERVIEW);
    render(<ReportsPanel api={api} />);

    expect(await screen.findByText("orangu")).toBeTruthy();
    expect(screen.getAllByText(ORANGU).length).toBeGreaterThan(0);
    expect(api).toHaveBeenCalledWith("/report-plugins");
  });

  it("saves a type with its class and variables", async () => {
    const api = vi.fn().mockResolvedValueOnce(OVERVIEW);
    api.mockResolvedValue(OVERVIEW);
    render(<ReportsPanel api={api} />);
    await screen.findByText("orangu");

    // Choosing the listed type fills the form for editing.
    fireEvent.click(screen.getByText("orangu"));
    expect(screen.getByLabelText("Report type").value).toBe("orangu");
    fireEvent.change(screen.getByLabelText("Variables"), {
      target: { value: "path=/usr/bin/orangu" },
    });
    fireEvent.submit(screen.getByLabelText("Save report type").closest("form"));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith(
        "/report-plugins/orangu",
        expect.objectContaining({ method: "PUT" }),
      ),
    );
    const put = api.mock.calls.find((c) => c[1]?.method === "PUT");
    expect(JSON.parse(put[1].body)).toEqual({
      className: ORANGU,
      variables: "path=/usr/bin/orangu",
    });
    await screen.findByText("Report type orangu saved.");
  });

  it("removes a type", async () => {
    const api = vi.fn(async () => OVERVIEW);
    render(<ReportsPanel api={api} />);
    fireEvent.click(await screen.findByLabelText("Remove report type orangu"));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/report-plugins/orangu", {
        method: "DELETE",
      }),
    );
  });

  it("shows a server rejection", async () => {
    const api = vi
      .fn()
      .mockResolvedValueOnce(OVERVIEW)
      .mockRejectedValueOnce(new Error("Not an installed report type class"));
    render(<ReportsPanel api={api} />);
    fireEvent.click(await screen.findByText("orangu"));
    fireEvent.submit(screen.getByLabelText("Save report type").closest("form"));

    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain(
        "Not an installed report type class",
      ),
    );
  });
});
