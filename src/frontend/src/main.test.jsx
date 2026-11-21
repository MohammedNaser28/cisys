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
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./main";

const { keycloakMock } = vi.hoisted(() => ({
  keycloakMock: {
    init: vi.fn(),
    updateToken: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    token: "test-token",
  },
}));

vi.mock("keycloak-js", () => ({
  default: class {
    constructor() {
      Object.assign(this, keycloakMock);
    }
  },
}));

vi.mock("./ProjectBuild", () => ({
  default: ({ name, onBack }) => (
    <main>
      <h1>Build page {name}</h1>
      <button type="button" onClick={onBack}>
        Back to project
      </button>
    </main>
  ),
}));

const branding = {
  name: "cisys",
  headerColor: "#176a4a",
  headingColor: "#176a4a",
  buttonColor: "#176a4a",
};

const stats = {
  projects: 1,
  builds: 1,
  passed: 1,
  failed: 0,
  running: 0,
  successRate: 1,
  buildsLast7Days: 1,
  lastBuildAt: null,
};

const project = {
  id: 1,
  name: "demo",
  repositoryUrl: "https://example.test/demo.git",
  privateRepository: false,
};

function json(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    headers: { get: () => null },
  };
}

function projectPage() {
  return { project, builds: [] };
}

function commitPage() {
  return {
    commits: [
      {
        revision: "a".repeat(40),
        message: "Commit message",
        author: "Ada",
        authoredAt: "2026-01-02T03:04:05Z",
        builds: [
          {
            id: 7,
            number: 3,
            status: "PASSED",
            branch: "feature/x",
            trigger: "MANUAL",
          },
        ],
      },
    ],
    hasMore: false,
    reason: null,
  };
}

function fetchResponse(input) {
  const url = new URL(String(input));
  if (
    url.pathname === "/api/branding/public" ||
    url.pathname === "/api/branding"
  )
    return json(branding);
  if (url.pathname === "/api/stats/public") return json(stats);
  if (url.pathname === "/api/session")
    return json({ username: "lead", subject: "lead", roles: ["lead"] });
  if (url.pathname === "/api/projects") return json([project]);
  if (url.pathname === "/api/teams") return json([]);
  if (url.pathname === "/api/projects/1/team")
    return json([{ username: "lead", role: "LEAD" }]);
  if (url.pathname === "/api/project-pages/demo/branches")
    return json({ branches: ["main"] });
  if (url.pathname === "/api/project-pages/demo/commits")
    return json(commitPage());
  if (url.pathname === "/api/project-pages/demo") return json(projectPage());
  throw new Error("unexpected fetch " + url);
}

let fetchMock;

const FIND = { timeout: 5000 };

const commitCalls = () =>
  fetchMock.mock.calls.filter(([url]) => String(url).includes("/commits?"));

function goTo(path) {
  window.history.replaceState(null, "", path);
}

function activeTab(name) {
  return screen.getByRole("tab", { name }).dataset.state;
}

beforeEach(() => {
  keycloakMock.init.mockReset().mockResolvedValue(false);
  keycloakMock.updateToken.mockReset().mockResolvedValue(true);
  keycloakMock.login.mockReset();
  keycloakMock.logout.mockReset();
  localStorage.clear();
  goTo("/");
  fetchMock = vi.fn(fetchResponse);
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("project tab routing", () => {
  it("starts on Builds when the project URL has no tab", async () => {
    goTo("/demo/");
    render(<App />);

    expect(await screen.findByText("No builds", {}, FIND)).toBeTruthy();
    expect(activeTab("Builds")).toBe("active");
    expect(window.location.search).toBe("");
    expect(commitCalls()).toHaveLength(0);
  });

  it("opens Commits from a deep link and keeps other query parameters", async () => {
    goTo("/demo/?tab=commits&x=1");
    render(<App />);

    expect(await screen.findByText("Commit message", {}, FIND)).toBeTruthy();
    expect(activeTab("Commits")).toBe("active");
    expect(window.location.search).toBe("?tab=commits&x=1");
    expect(commitCalls()).toHaveLength(1);
  });

  it("switches tabs with replaceState and preserves other query parameters", async () => {
    goTo("/demo/?x=1#details");
    render(<App />);
    expect(await screen.findByText("No builds", {}, FIND)).toBeTruthy();

    const replace = vi.spyOn(window.history, "replaceState");
    const push = vi.spyOn(window.history, "pushState");
    const historyLength = window.history.length;

    fireEvent.mouseDown(screen.getByRole("tab", { name: "Commits" }));
    expect(await screen.findByText("Commit message", {}, FIND)).toBeTruthy();
    expect(window.location.search).toBe("?x=1&tab=commits");
    expect(window.location.hash).toBe("#details");
    expect(replace).toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
    expect(window.history.length).toBe(historyLength);

    fireEvent.mouseDown(screen.getByRole("tab", { name: "Builds" }));
    expect(await screen.findByText("No builds", {}, FIND)).toBeTruthy();
    expect(window.location.search).toBe("?x=1");
    expect(window.location.hash).toBe("#details");

    replace.mockRestore();
    push.mockRestore();
  });

  it("opens a build with pushState and Back restores the Commits tab and context", async () => {
    goTo("/demo/?tab=commits&x=1#details");
    render(<App />);
    expect(await screen.findByText("Commit message", {}, FIND)).toBeTruthy();

    const push = vi.spyOn(window.history, "pushState");
    fireEvent.click(screen.getByRole("link", { name: "#3" }));
    expect(await screen.findByText("Build page demo", {}, FIND)).toBeTruthy();
    expect(push).toHaveBeenCalled();
    expect(window.location.pathname).toBe("/demo/feature%2Fx/build/7");
    expect(window.location.search).toBe("?tab=commits&x=1");
    expect(window.location.hash).toBe("#details");
    push.mockRestore();

    window.history.back();
    await waitFor(() => expect(window.location.pathname).toBe("/demo/"), FIND);
    expect(window.location.search).toBe("?tab=commits&x=1");
    expect(window.location.hash).toBe("#details");
    expect(await screen.findByText("Commit message", {}, FIND)).toBeTruthy();
    expect(activeTab("Commits")).toBe("active");
  });

  it("returns to the project with the same query and hash from the build page", async () => {
    goTo("/demo/?tab=commits&x=1#details");
    render(<App />);
    expect(await screen.findByText("Commit message", {}, FIND)).toBeTruthy();

    fireEvent.click(screen.getByRole("link", { name: "#3" }));
    expect(await screen.findByText("Build page demo", {}, FIND)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Back to project" }));

    expect(await screen.findByText("Commit message", {}, FIND)).toBeTruthy();
    expect(window.location.pathname).toBe("/demo/");
    expect(window.location.search).toBe("?tab=commits&x=1");
    expect(window.location.hash).toBe("#details");
    expect(activeTab("Commits")).toBe("active");
  });

  it("starts on Builds when a project is opened from the project list", async () => {
    keycloakMock.init.mockResolvedValue(true);
    goTo("/");
    render(<App />);

    const link = await screen.findByRole("link", { name: "demo" }, FIND);
    fireEvent.click(link);

    expect(await screen.findByText("No builds", {}, FIND)).toBeTruthy();
    expect(window.location.pathname).toBe("/demo/");
    expect(window.location.search).toBe("");
    expect(activeTab("Builds")).toBe("active");
    expect(commitCalls()).toHaveLength(0);
  });
});

describe("team-only navigation", () => {
  it("shows Teams instead of Users to a team LEAD without a global lead role", async () => {
    keycloakMock.init.mockResolvedValue(true);
    fetchMock.mockImplementation((input) => {
      const url = new URL(String(input));
      if (url.pathname === "/api/session")
        return json({ username: "team", subject: "team", roles: ["member"] });
      if (url.pathname === "/api/teams")
        return json([
          {
            id: 3,
            name: "Build team",
            role: "LEAD",
            projectCount: 0,
            memberCount: 1,
            tokenSet: false,
          },
        ]);
      return fetchResponse(input);
    });
    render(<App />);

    const teams = await screen.findByRole("button", { name: "Teams" }, FIND);
    expect(screen.queryByRole("button", { name: "Users" })).toBeNull();
    fireEvent.click(teams);
    expect(
      await screen.findByRole("heading", { level: 1, name: "Teams" }, FIND),
    ).toBeTruthy();
    expect(screen.queryByRole("tab", { name: "Users" })).toBeNull();
  });

  it("redirects a member with no LEAD team away from the Users route", async () => {
    keycloakMock.init.mockResolvedValue(true);
    goTo("/users");
    fetchMock.mockImplementation((input) => {
      const url = new URL(String(input));
      if (url.pathname === "/api/session")
        return json({
          username: "member",
          subject: "member",
          roles: ["member"],
        });
      if (url.pathname === "/api/teams")
        return json([
          {
            id: 3,
            name: "Build team",
            role: "MEMBER",
            projectCount: 0,
            memberCount: 1,
            tokenSet: false,
          },
        ]);
      return fetchResponse(input);
    });
    render(<App />);

    await waitFor(() => expect(window.location.pathname).toBe("/home"), FIND);
  });
});

describe("audit navigation", () => {
  it("is hidden from non-admins", async () => {
    keycloakMock.init.mockResolvedValue(true);
    render(<App />);
    await screen.findByRole("button", { name: "Projects" }, FIND);
    expect(screen.queryByRole("button", { name: "Audit" })).toBeNull();
  });

  it("opens the audit log for admins", async () => {
    keycloakMock.init.mockResolvedValue(true);
    fetchMock.mockImplementation((input) => {
      const url = new URL(String(input));
      if (url.pathname === "/api/session")
        return json({ username: "admin", subject: "admin", roles: ["admin"] });
      if (url.pathname === "/api/audit")
        return json({
          total: 1,
          events: [
            {
              id: 7,
              occurredAt: "2026-01-02T03:04:05Z",
              action: "project.create",
              outcome: "SUCCESS",
              actorUsername: "admin",
              targetType: "PROJECT",
              targetName: "demo",
              summary: "Created project demo",
            },
          ],
        });
      return fetchResponse(input);
    });
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Audit" }, FIND));
    expect(
      await screen.findByText("Created project demo", {}, FIND),
    ).toBeTruthy();
    expect(
      screen
        .getByRole("button", { name: "Audit" })
        .getAttribute("aria-current"),
    ).toBe("page");
  });
});
