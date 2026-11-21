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
import UsersPage from "./UsersPage";

// Radix Select and Popover use pointer capture and ResizeObserver, which jsdom
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

const users = {
  total: 2,
  users: [
    {
      id: "a",
      username: "boss",
      role: "admin",
      enabled: true,
      manageable: false,
      projects: [],
    },
    {
      id: "m",
      username: "dev",
      role: "member",
      enabled: true,
      manageable: true,
      projects: [{ id: 1, name: "alpha" }],
    },
  ],
};

describe("UsersPage", () => {
  it("shows Teams for a team-only user with no Users tab", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(path === "/teams" ? [] : users),
    );
    render(<UsersPage api={load} user={{ roles: ["member"], subject: "t" }} />);

    expect(
      await screen.findByRole("heading", { level: 1, name: "Teams" }),
    ).toBeTruthy();
    expect(screen.queryByRole("tab", { name: "Users" })).toBeNull();
  });

  it("shows Users and Teams tabs for lead/admin and switches sections", async () => {
    const load = vi.fn((path) =>
      Promise.resolve(
        path === "/teams"
          ? []
          : path === "/users/projects"
            ? [{ id: 1, name: "alpha" }]
            : users,
      ),
    );
    render(<UsersPage api={load} user={{ roles: ["lead"], subject: "l" }} />);

    expect(
      screen.getByRole("heading", { level: 1, name: "Users" }),
    ).toBeTruthy();
    expect(
      screen.getAllByRole("heading", { level: 1, name: "Users" }).length,
    ).toBe(1);
    expect(screen.getByRole("tab", { name: "Users" })).toBeTruthy();
    expect(screen.getByRole("tab", { name: "Teams" })).toBeTruthy();

    fireEvent.click(screen.getByRole("tab", { name: "Teams" }));
    await waitFor(() => {
      expect(screen.queryByRole("heading", { name: "Add a user" })).toBeNull();
    });
  });
});
