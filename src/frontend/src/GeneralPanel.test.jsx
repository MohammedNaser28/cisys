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
import GeneralPanel from "./GeneralPanel";

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

describe("GeneralPanel", () => {
  it("loads and saves who may write articles", async () => {
    const api = vi
      .fn()
      .mockResolvedValueOnce({ articleEditors: "ADMIN" })
      .mockResolvedValueOnce({ articleEditors: "MEMBER" });
    render(<GeneralPanel api={api} />);

    await waitFor(() =>
      expect(screen.getByLabelText("Article editors").textContent).toContain(
        "Admins",
      ),
    );
    expect(api).toHaveBeenCalledWith("/settings/general");
    expect(screen.getByText("Articles")).toBeTruthy();

    const trigger = screen.getByLabelText("Article editors");
    fireEvent.pointerDown(trigger, { button: 0, pointerType: "mouse" });
    fireEvent.pointerUp(trigger, { button: 0, pointerType: "mouse" });
    fireEvent.click(await screen.findByRole("option", { name: "All users" }));
    fireEvent.click(screen.getByLabelText("Save article settings"));

    expect(await screen.findByText("Article settings saved.")).toBeTruthy();
    expect(api).toHaveBeenLastCalledWith("/settings/general", {
      method: "PUT",
      body: JSON.stringify({ articleEditors: "MEMBER" }),
    });
  });
});
