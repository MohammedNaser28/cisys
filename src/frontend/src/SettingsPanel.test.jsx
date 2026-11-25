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
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import "./i18n";
import SettingsPanel from "./SettingsPanel";

vi.mock("./BrandingPanel", () => ({ default: () => null }));
vi.mock("./GeneralPanel", () => ({ default: () => null }));
vi.mock("./NetworkPanel", () => ({ default: () => null }));
vi.mock("./PollingPanel", () => ({ default: () => null }));
vi.mock("./PullRequestPanel", () => ({ default: () => null }));
vi.mock("./ReportsPanel", () => ({ default: () => null }));

afterEach(cleanup);

function settingsApi() {
  return vi.fn(async (path, options) =>
    options
      ? JSON.parse(options.body)
      : path === "/settings/artifacts"
        ? { maxSizeMib: 100, maxFiles: 1000 }
        : {},
  );
}

describe("SettingsPanel", () => {
  it("has no artifacts tab and no untranslated tab titles", async () => {
    render(<SettingsPanel api={settingsApi()} />);
    await screen.findByLabelText("Max artifact files per build");

    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
      "General",
      "Retention",
      "Builds",
      "Checkout",
      "Performance",
      "Reports",
      "Network",
      "Branding",
    ]);
    expect(document.body.textContent).not.toContain("settings.");
  });

  it("saves the artifact limits from the General tab", async () => {
    const api = settingsApi();
    render(<SettingsPanel api={api} />);
    const general = within(screen.getByRole("tabpanel", { name: "General" }));

    expect(general.getByText("Artifacts")).toBeTruthy();
    const size = await general.findByLabelText(
      "Max artifact size per build (MiB)",
    );
    expect(size.value).toBe("100");
    fireEvent.change(size, { target: { value: "200" } });
    fireEvent.click(general.getByLabelText("Save artifact settings"));

    expect(await general.findByText("Artifact settings saved.")).toBeTruthy();
    expect(api).toHaveBeenLastCalledWith("/settings/artifacts", {
      method: "PUT",
      body: JSON.stringify({ maxSizeMib: 200, maxFiles: 1000 }),
    });
  });
});
