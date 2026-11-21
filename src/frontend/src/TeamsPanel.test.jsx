/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THE ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS FILE CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

import React from "react";
import "./i18n";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import TeamsPanel from "./TeamsPanel";

// Radix Select uses pointer capture and ResizeObserver, which jsdom lacks.
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

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const lead = { username: "lead", roles: ["lead"] };
const team = {
  id: 3,
  name: "Build team",
  role: "LEAD",
  projectCount: 1,
  memberCount: 1,
  tokenSet: true,
};
const projects = [
  { id: 9, name: "private", teamId: 3, privateRepository: true },
];

async function choose(label, option) {
  const trigger = screen.getByLabelText(label);
  await waitFor(() => expect(trigger.disabled).toBe(false));
  fireEvent.pointerDown(trigger, {
    button: 0,
    ctrlKey: false,
    pointerType: "mouse",
  });
  fireEvent.pointerUp(trigger, {
    button: 0,
    ctrlKey: false,
    pointerType: "mouse",
  });
  fireEvent.click(await screen.findByRole("option", { name: option }));
}

function apiMock(calls, options = {}) {
  const listed = options.teams ?? [team];
  return vi.fn(async (path, request = {}) => {
    const body = request.body ? JSON.parse(request.body) : undefined;
    calls.push([request.method || "GET", path, body]);
    if (path === "/teams" && !request.method) return listed;
    if (path === "/projects" && !request.method) {
      if (options.projectsError)
        throw Object.assign(new Error("Projects are unavailable."), {
          status: 500,
        });
      if (options.projectsPending) return new Promise(() => {});
      return options.projects ?? projects;
    }
    if (path === "/teams/3/members" && !request.method)
      return [{ username: "lead", role: "LEAD" }];
    if (path === "/teams/3/token" && request.method === "PUT")
      return {
        set: true,
        updatedAt: "2026-01-01T00:00:00Z",
        updatedBy: "lead",
      };
    if (path === "/teams/3/token" && request.method === "DELETE")
      return {
        set: false,
        updatedAt: "2026-01-01T00:00:00Z",
        updatedBy: "lead",
      };
    if (path === "/teams" && request.method === "POST")
      return {
        id: 4,
        name: body.name,
        role: "LEAD",
        projectCount: 0,
        memberCount: 1,
        tokenSet: false,
      };
    if (path === "/teams/3" && request.method === "PUT") return team;
    if (path === "/teams/3" && request.method === "DELETE") return undefined;
    if (path === "/teams/3/members" && request.method === "POST")
      return { username: body.username, role: body.role };
    if (path === "/teams/3/members/lead" && request.method === "PUT")
      return { username: "lead", role: body.role };
    if (path === "/teams/3/members/lead" && request.method === "DELETE")
      return undefined;
    throw new Error(`unexpected ${path}`);
  });
}

describe("TeamsPanel", () => {
  it("lists teams as rows with tags and icon-only actions", async () => {
    const calls = [];
    render(<TeamsPanel api={apiMock(calls)} user={lead} />);

    expect(await screen.findByText("Build team")).toBeTruthy();
    expect(await screen.findByText("1 member")).toBeTruthy();
    expect(screen.getByText("1 project")).toBeTruthy();
    expect(screen.getByText("Token set")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Edit Build team" }),
    ).toBeTruthy();
    expect(
      screen.getByRole("button", {
        name: "Set or clear token for Build team",
      }),
    ).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Delete Build team" }),
    ).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Create" })).toBeNull();
    expect(
      calls.some(
        ([method, path]) => method === "GET" && path === "/teams/3/members",
      ),
    ).toBe(false);
  });

  it("shows a loading state while private project choices load", async () => {
    const calls = [];
    render(
      <TeamsPanel
        api={apiMock(calls, { projectsPending: true })}
        user={lead}
      />,
    );

    fireEvent.click(
      await screen.findByRole("button", {
        name: "Set or clear token for Build team",
      }),
    );

    expect(await screen.findByText("Loading private projects…")).toBeTruthy();
    expect(
      calls.some(([method, path]) => method === "GET" && path === "/projects"),
    ).toBe(true);
  });

  it("lists private projects of the team for token validation", async () => {
    const calls = [];
    render(<TeamsPanel api={apiMock(calls)} user={lead} />);

    fireEvent.click(
      await screen.findByRole("button", {
        name: "Set or clear token for Build team",
      }),
    );

    await choose("Validate against private project", "private");
    fireEvent.change(screen.getByLabelText("Token"), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save token" }));
    await waitFor(() =>
      expect(calls).toContainEqual([
        "PUT",
        "/teams/3/token",
        { token: "secret", projectId: 9 },
      ]),
    );
  });

  it("explains when no private projects can validate the token", async () => {
    const calls = [];
    render(<TeamsPanel api={apiMock(calls, { projects: [] })} user={lead} />);

    fireEvent.click(
      await screen.findByRole("button", {
        name: "Set or clear token for Build team",
      }),
    );

    expect(
      await screen.findByText(
        "No private projects use this team. The token can be stored without validation.",
      ),
    ).toBeTruthy();
    await choose(
      "Validate against private project",
      "Store without validation",
    );
  });

  it("keeps token validation optional when projects cannot be loaded", async () => {
    const calls = [];
    render(
      <TeamsPanel api={apiMock(calls, { projectsError: true })} user={lead} />,
    );

    fireEvent.click(
      await screen.findByRole("button", {
        name: "Set or clear token for Build team",
      }),
    );

    expect(
      await screen.findByText(
        /Projects are unavailable\. You can still store the token without validation\./,
      ),
    ).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Token"), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save token" }));
    await waitFor(() =>
      expect(calls).toContainEqual([
        "PUT",
        "/teams/3/token",
        { token: "secret", projectId: null },
      ]),
    );
  });

  it("creates a team with the plus icon button", async () => {
    const calls = [];
    render(<TeamsPanel api={apiMock(calls)} user={lead} />);

    fireEvent.change(await screen.findByLabelText("Team name"), {
      target: { value: "New team" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add team" }));

    await waitFor(() =>
      expect(calls).toContainEqual(["POST", "/teams", { name: "New team" }]),
    );
  });

  it("edits the name and members from the pencil action", async () => {
    const calls = [];
    render(<TeamsPanel api={apiMock(calls)} user={lead} />);

    await screen.findByText("1 member");
    fireEvent.click(screen.getByRole("button", { name: "Edit Build team" }));
    const editForm = screen.getByRole("form", { name: "Edit Build team" });
    expect(editForm.className).toContain("is-selected");
    expect(editForm.getAttribute("data-selected")).toBe("true");
    fireEvent.change(screen.getByLabelText("Team name for Build team"), {
      target: { value: "Platform team" },
    });

    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "dev" },
    });
    await choose("New member role", "lead");
    fireEvent.click(screen.getByRole("button", { name: "Add member" }));
    await waitFor(() =>
      expect(calls).toContainEqual([
        "POST",
        "/teams/3/members",
        { username: "dev", role: "LEAD" },
      ]),
    );

    await waitFor(() =>
      expect(screen.getByLabelText("Role for lead").disabled).toBe(false),
    );
    await choose("Role for lead", "member");
    await waitFor(() =>
      expect(calls).toContainEqual([
        "PUT",
        "/teams/3/members/lead",
        { role: "MEMBER" },
      ]),
    );

    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Remove lead from the team" })
          .disabled,
      ).toBe(false),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Remove lead from the team" }),
    );
    await waitFor(() =>
      expect(calls).toContainEqual([
        "DELETE",
        "/teams/3/members/lead",
        undefined,
      ]),
    );

    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Save Build team" }).disabled,
      ).toBe(false),
    );
    fireEvent.click(screen.getByRole("button", { name: "Save Build team" }));
    await waitFor(() =>
      expect(calls).toContainEqual([
        "PUT",
        "/teams/3",
        { name: "Platform team" },
      ]),
    );
  });

  it("refetches members each time the editor is reopened", async () => {
    const calls = [];
    render(<TeamsPanel api={apiMock(calls)} user={lead} />);

    const memberFetches = () =>
      calls.filter(
        ([method, path]) => method === "GET" && path === "/teams/3/members",
      ).length;

    fireEvent.click(
      await screen.findByRole("button", { name: "Edit Build team" }),
    );
    await waitFor(() =>
      expect(screen.getByLabelText("Role for lead")).toBeTruthy(),
    );
    expect(memberFetches()).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() =>
      expect(
        screen.queryByRole("form", { name: "Edit Build team" }),
      ).toBeNull(),
    );

    fireEvent.click(screen.getByRole("button", { name: "Edit Build team" }));
    await waitFor(() =>
      expect(screen.getByLabelText("Role for lead")).toBeTruthy(),
    );
    // A cached first response would leave another LEAD's changes invisible.
    expect(memberFetches()).toBe(2);
  });

  it("sets and clears a write-only token with icons", async () => {
    const calls = [];
    render(<TeamsPanel api={apiMock(calls)} user={lead} />);

    fireEvent.click(
      await screen.findByRole("button", {
        name: "Set or clear token for Build team",
      }),
    );
    const tokenForm = screen.getByRole("form", {
      name: "Token for Build team",
    });
    expect(tokenForm.className).toContain("is-selected");
    expect(tokenForm.getAttribute("data-selected")).toBe("true");
    fireEvent.change(screen.getByLabelText("Token"), {
      target: { value: "secret" },
    });
    await choose("Validate against private project", "private");
    fireEvent.click(screen.getByRole("button", { name: "Save token" }));

    await waitFor(() =>
      expect(calls).toContainEqual([
        "PUT",
        "/teams/3/token",
        { token: "secret", projectId: 9 },
      ]),
    );
    expect(screen.getByLabelText("Token").value).toBe("");
    expect(screen.queryByDisplayValue("secret")).toBeNull();
    expect(screen.getByText("Token set.")).toBeTruthy();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Clear token" }).disabled).toBe(
        false,
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "Clear token" }));
    await waitFor(() =>
      expect(calls).toContainEqual(["DELETE", "/teams/3/token", undefined]),
    );
  });

  it("reflects the stored token state after loading and after changes", async () => {
    const calls = [];
    const noToken = { ...team, tokenSet: false };
    render(
      <TeamsPanel api={apiMock(calls, { teams: [noToken] })} user={lead} />,
    );

    await screen.findByText("Build team");
    expect(screen.queryByText("Token set")).toBeNull();

    fireEvent.click(
      screen.getByRole("button", {
        name: "Set or clear token for Build team",
      }),
    );
    fireEvent.change(screen.getByLabelText("Token"), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save token" }));
    await screen.findByText("Token set.");
    // The row badge is replaced by the token editor, so close it first.
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("Token set")).toBeTruthy();

    fireEvent.click(
      screen.getByRole("button", {
        name: "Set or clear token for Build team",
      }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Clear token" }));
    await waitFor(() =>
      expect(calls).toContainEqual(["DELETE", "/teams/3/token", undefined]),
    );
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByText("Token set")).toBeNull());
  });

  it("confirms deletion and names the project count", async () => {
    const calls = [];
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<TeamsPanel api={apiMock(calls)} user={lead} />);

    fireEvent.click(
      await screen.findByRole("button", { name: "Delete Build team" }),
    );

    await waitFor(() =>
      expect(calls).toContainEqual(["DELETE", "/teams/3", undefined]),
    );
    expect(confirm).toHaveBeenCalledWith(
      "Delete Build team? This will detach 1 project.",
    );
  });

  it("hides management icons from a member who cannot manage the team", async () => {
    const calls = [];
    const memberTeam = { ...team, role: "MEMBER", tokenSet: false };
    render(
      <TeamsPanel
        api={apiMock(calls, { teams: [memberTeam] })}
        user={{ username: "dev", roles: ["member"] }}
      />,
    );

    expect(await screen.findByText("Build team")).toBeTruthy();
    expect(
      screen.queryByRole("button", { name: "Edit Build team" }),
    ).toBeNull();
    expect(
      screen.queryByRole("button", {
        name: "Set or clear token for Build team",
      }),
    ).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Delete Build team" }),
    ).toBeNull();
    expect(screen.queryByRole("button", { name: "Add team" })).toBeNull();
  });

  it("takes token state from a reload rather than the cached mutation result", async () => {
    let listed = [{ ...team, tokenSet: false }];
    const api = vi.fn(async (path, request = {}) => {
      if (path === "/teams" && !request.method) return listed;
      if (path === "/teams" && request.method === "POST") {
        listed = [
          ...listed,
          {
            id: 4,
            name: "Second team",
            role: "LEAD",
            projectCount: 0,
            memberCount: 1,
            tokenSet: false,
          },
        ];
        return listed[1];
      }
      if (path === "/teams/3/token" && request.method === "PUT")
        return {
          set: true,
          updatedAt: "2026-01-01T00:00:00Z",
          updatedBy: "lead",
        };
      throw new Error(`unexpected ${path}`);
    });
    render(<TeamsPanel api={api} user={lead} />);

    fireEvent.click(
      await screen.findByRole("button", {
        name: "Set or clear token for Build team",
      }),
    );
    fireEvent.change(screen.getByLabelText("Token"), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save token" }));
    await screen.findByText("Token set.");
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("Token set")).toBeTruthy();

    // A reload reports the token as cleared by someone else; the cached
    // mutation result must not keep claiming it is set.
    fireEvent.change(screen.getByLabelText("Team name"), {
      target: { value: "Second team" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add team" }));
    await waitFor(() => expect(screen.queryByText("Token set")).toBeNull());
  });

  it("ignores a stale team list response that arrives after a newer one", async () => {
    let requests = 0;
    let resolveFirst;
    const api = vi.fn((path, request = {}) => {
      if (path === "/teams" && !request.method) {
        requests += 1;
        if (requests === 1)
          return new Promise((resolve) => {
            resolveFirst = resolve;
          });
        return Promise.resolve([team]);
      }
      if (path === "/teams" && request.method === "POST")
        return Promise.resolve({
          id: 4,
          name: "Second team",
          role: "LEAD",
          projectCount: 0,
          memberCount: 1,
          tokenSet: false,
        });
      return Promise.reject(new Error(`unexpected ${path}`));
    });
    render(<TeamsPanel api={api} user={lead} />);

    fireEvent.change(screen.getByLabelText("Team name"), {
      target: { value: "Second team" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add team" }));
    expect(await screen.findByText("Build team")).toBeTruthy();

    // The first request finally answers with an empty, obsolete list.
    resolveFirst([]);
    await waitFor(() => expect(screen.getByText("Build team")).toBeTruthy());
  });
});
