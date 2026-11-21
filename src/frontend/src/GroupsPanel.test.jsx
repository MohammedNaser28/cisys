/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THE ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import GroupsPanel from "./GroupsPanel";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const projects = [
  { id: 1, name: "alpha" },
  { id: 2, name: "beta" },
  { id: 3, name: "gamma" },
];
const projectRoles = new Map([
  [1, "LEAD"],
  [2, "MEMBER"],
  [3, null],
]);

function rejection(status, message) {
  return Promise.reject(Object.assign(new Error(message), { status }));
}

function makeApi({ groupRole = "LEAD", fail } = {}) {
  const state = {
    groups: [{ id: 1, name: "platform", role: groupRole }],
    members: [
      { username: "alice", role: "LEAD" },
      { username: "bob", role: "MEMBER" },
    ],
    links: [],
  };
  const calls = [];
  const api = vi.fn((path, options = {}) => {
    const method = options.method ?? "GET";
    calls.push({ path, options });
    if (fail?.path === path && fail.method === method) {
      return rejection(fail.status, fail.message);
    }
    if (path === "/groups" && method === "GET")
      return Promise.resolve(state.groups);
    if (path === "/groups" && method === "POST") {
      const group = {
        id: 2,
        name: JSON.parse(options.body).name,
        role: "LEAD",
      };
      state.groups.push(group);
      return Promise.resolve(group);
    }
    if (path === "/groups/1" && method === "DELETE") {
      state.groups = [];
      return Promise.resolve(null);
    }
    if (path === "/groups/1/members" && method === "GET") {
      return Promise.resolve([...state.members]);
    }
    if (path === "/groups/1/members" && method === "POST") {
      state.members.push(JSON.parse(options.body));
      return Promise.resolve(state.members.at(-1));
    }
    if (path === "/groups/1/projects" && method === "GET") {
      return Promise.resolve([...state.links]);
    }
    if (path === "/groups/1/projects" && method === "POST") {
      const link = JSON.parse(options.body);
      const project = projects.find((item) => item.id === link.projectId);
      state.links.push({ ...link, id: project.id, name: project.name });
      return Promise.resolve(state.links.at(-1));
    }
    const member = path.match(/^\/groups\/1\/members\/(.+)$/);
    if (member && method === "PUT") {
      const value = JSON.parse(options.body);
      state.members = state.members.map((item) =>
        item.username === decodeURIComponent(member[1])
          ? { ...item, ...value }
          : item,
      );
      return Promise.resolve(
        state.members.find(
          (item) => item.username === decodeURIComponent(member[1]),
        ),
      );
    }
    if (member && method === "DELETE") {
      state.members = state.members.filter(
        (item) => item.username !== decodeURIComponent(member[1]),
      );
      return Promise.resolve(null);
    }
    const link = path.match(/^\/groups\/1\/projects\/(\d+)$/);
    if (link && method === "PUT") {
      const value = JSON.parse(options.body);
      state.links = state.links.map((item) =>
        item.id === Number(link[1]) ? { ...item, ...value } : item,
      );
      return Promise.resolve(
        state.links.find((item) => item.id === Number(link[1])),
      );
    }
    if (link && method === "DELETE") {
      state.links = state.links.filter((item) => item.id !== Number(link[1]));
      return Promise.resolve(null);
    }
    throw new Error(`Unexpected API call: ${method} ${path}`);
  });
  return { api, calls, state };
}

function renderPanel({ roles = ["member"], groupRole = "LEAD", fail } = {}) {
  const fixture = makeApi({ groupRole, fail });
  render(
    <GroupsPanel
      api={fixture.api}
      user={{ username: "alice", roles }}
      projects={projects}
      projectRoles={projectRoles}
    />,
  );
  return fixture;
}

describe("GroupsPanel", () => {
  it("renders groups and the empty state", async () => {
    renderPanel();
    expect(await screen.findAllByText("platform")).toHaveLength(2);
    expect(screen.getByText("Your role: LEAD")).toBeTruthy();

    const empty = makeApi();
    empty.state.groups = [];
    cleanup();
    render(
      <GroupsPanel
        api={empty.api}
        user={{ username: "alice", roles: ["member"] }}
        projects={[]}
        projectRoles={new Map()}
      />,
    );
    expect(await screen.findByText("No groups yet")).toBeTruthy();
  });

  it.each([["lead"], ["admin"]])(
    "allows a global %s to create a group",
    async (role) => {
      const { api } = renderPanel({ roles: [role] });
      fireEvent.change(screen.getByLabelText("Group name"), {
        target: { value: "release" },
      });
      fireEvent.click(screen.getByRole("button", { name: "Create" }));
      expect(await screen.findAllByText("release")).toHaveLength(2);
      expect(api).toHaveBeenCalledWith(
        "/groups",
        expect.objectContaining({ method: "POST" }),
      );
    },
  );

  it("hides creation for a plain global member", async () => {
    renderPanel({ roles: ["member"], groupRole: null });
    await screen.findAllByText("platform");
    expect(screen.queryByLabelText("Group name")).toBeNull();
    expect(screen.queryByRole("button", { name: "Create" })).toBeNull();
  });

  it("adds, changes, and removes members, while non-managers get no controls", async () => {
    const fixture = renderPanel();
    expect(await screen.findByText("alice (you)")).toBeTruthy();
    expect(screen.queryByText("lead", { exact: true })).toBeNull();
    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "carol" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add member" }));
    await waitFor(() => expect(screen.getByText("carol")).toBeTruthy());

    fireEvent.change(screen.getByLabelText("Group role for bob"), {
      target: { value: "LEAD" },
    });
    await waitFor(() =>
      expect(
        fixture.state.members.find((member) => member.username === "bob").role,
      ).toBe("LEAD"),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Remove carol from group" }),
    );
    await waitFor(() => expect(screen.queryByText("carol")).toBeNull());

    cleanup();
    renderPanel({ roles: ["member"], groupRole: "MEMBER" });
    await screen.findByText("alice (you)");
    expect(screen.queryByLabelText("Username")).toBeNull();
    expect(screen.queryByLabelText("Group role for bob")).toBeNull();
    expect(screen.queryByRole("button", { name: "Remove bob" })).toBeNull();
  });

  it("links a manageable project as MEMBER, changes the link role, and unlinks it", async () => {
    const fixture = renderPanel();
    await screen.findByText("alpha");
    const picker = screen.getByLabelText("Project");
    expect(
      Array.from(picker.options).map((option) => option.textContent),
    ).toEqual(["Select a project", "alpha"]);
    fireEvent.change(picker, { target: { value: "1" } });
    fireEvent.click(screen.getByRole("button", { name: "Link project" }));
    await waitFor(() => expect(screen.getByText("alpha")).toBeTruthy());
    expect(
      JSON.parse(
        fixture.calls.find(
          (call) =>
            call.path === "/groups/1/projects" &&
            call.options.method === "POST",
        ).options.body,
      ),
    ).toEqual({
      projectId: 1,
      role: "MEMBER",
    });

    fireEvent.change(
      screen.getByLabelText("Project access for group in alpha"),
      {
        target: { value: "LEAD" },
      },
    );
    await waitFor(() => expect(fixture.state.links[0].role).toBe("LEAD"));
    fireEvent.click(screen.getByRole("button", { name: "Unlink" }));
    await waitFor(() =>
      expect(
        screen.queryByLabelText("Project access for group in alpha"),
      ).toBeNull(),
    );
  });

  it("requires confirmation before deleting a group", async () => {
    const fixture = renderPanel();
    await screen.findAllByText("platform");
    const deleteButton = screen.getByRole("button", { name: "Delete group" });
    expect(deleteButton.dataset.variant).toBe("destructive");
    expect(deleteButton.className).toContain("w-fit");
    vi.spyOn(window, "confirm").mockReturnValue(false);
    fireEvent.click(deleteButton);
    expect(
      fixture.calls.some(
        (call) => call.options.method === "DELETE" && call.path === "/groups/1",
      ),
    ).toBe(false);
    window.confirm.mockReturnValue(true);
    fireEvent.click(deleteButton);
    await waitFor(() => expect(screen.getByText("No groups yet")).toBeTruthy());
  });

  it.each([
    [
      403,
      "You do not have permission to do that.",
      "POST",
      "/groups/1/projects",
    ],
    [
      409,
      "A group must keep at least one LEAD; add another LEAD first",
      "PUT",
      "/groups/1/members/alice",
    ],
    [404, "The group was not found.", "DELETE", "/groups/1"],
  ])(
    "shows a readable %s error without changing the group list",
    async (status, message, method, path) => {
      const fixture = renderPanel({
        fail: { status, message, method, path },
      });
      await screen.findAllByText("platform");
      if (path.endsWith("/members")) {
        fireEvent.change(screen.getByLabelText("Username"), {
          target: { value: "carol" },
        });
        fireEvent.click(screen.getByRole("button", { name: "Add member" }));
      } else if (path.includes("/members/")) {
        fireEvent.change(await screen.findByLabelText("Group role for alice"), {
          target: { value: "MEMBER" },
        });
      } else if (path.endsWith("/projects")) {
        fireEvent.change(screen.getByLabelText("Project"), {
          target: { value: "1" },
        });
        fireEvent.click(screen.getByRole("button", { name: "Link project" }));
      } else {
        vi.spyOn(window, "confirm").mockReturnValue(true);
        fireEvent.click(screen.getByRole("button", { name: "Delete group" }));
      }
      expect((await screen.findByRole("alert")).textContent).toContain(message);
      expect(screen.getAllByText("platform")).toHaveLength(2);
      expect(fixture.state.groups).toHaveLength(1);
    },
  );
});
