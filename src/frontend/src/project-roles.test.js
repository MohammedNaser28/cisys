import { describe, expect, it } from "vitest";
import {
  canBuildProject,
  canManageProject,
  effectiveProjectRole,
} from "./project-roles";

describe("project role resolution", () => {
  it("allows a team-only MEMBER to use project build controls", () => {
    const role = effectiveProjectRole(
      { id: 1, effectiveRole: "MEMBER" },
      { roles: ["member"] },
    );

    expect(role).toBe("MEMBER");
    expect(canBuildProject(role, false)).toBe(true);
    expect(canManageProject(role, false)).toBe(false);
  });

  it("allows a team-only LEAD to use project management controls", () => {
    const role = effectiveProjectRole(
      { id: 1, effectiveRole: "LEAD" },
      { roles: ["member"] },
    );

    expect(role).toBe("LEAD");
    expect(canBuildProject(role, false)).toBe(true);
    expect(canManageProject(role, false)).toBe(true);
  });

  it("does not infer project LEAD from a team lead", () => {
    const role = effectiveProjectRole(
      { id: 1, effectiveRole: "MEMBER" },
      { roles: ["member"] },
    );

    expect(role).toBe("MEMBER");
    expect(canManageProject(role, false)).toBe(false);
  });

  it("preserves direct project roles supplied as the effective role", () => {
    expect(
      effectiveProjectRole(
        { id: 1, effectiveRole: "LEAD" },
        { roles: ["lead"] },
      ),
    ).toBe("LEAD");
    expect(
      effectiveProjectRole(
        { id: 2, effectiveRole: "MEMBER" },
        { roles: ["member"] },
      ),
    ).toBe("MEMBER");
  });

  it("uses the backend's highest effective role when access paths coexist", () => {
    const role = effectiveProjectRole(
      { id: 1, effectiveRole: "LEAD" },
      { roles: ["member"] },
    );

    expect(canManageProject(role, false)).toBe(true);
    expect(canBuildProject(role, false)).toBe(true);
  });

  it("preserves global admin behavior", () => {
    const role = effectiveProjectRole(
      { id: 1, effectiveRole: null },
      { roles: ["admin"] },
    );

    expect(role).toBe("ADMIN");
    expect(canManageProject(role, true)).toBe(true);
    expect(canBuildProject(role, true)).toBe(true);
  });
});
