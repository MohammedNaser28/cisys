/**
 * Project roles are resolved by the backend. The frontend only uses the result to decide which
 * controls to render; every operation is still authorized by the backend.
 */
export function effectiveProjectRole(project, userInfo) {
  if (userInfo?.roles?.includes("admin")) return "ADMIN";
  return project?.effectiveRole ?? null;
}

export function canManageProject(role, isAdmin) {
  return isAdmin || role === "LEAD";
}

export function canBuildProject(role, isAdmin) {
  return isAdmin || role === "MEMBER" || role === "LEAD";
}
