/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THE ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

import React, { useCallback, useEffect, useState } from "react";
import { Users } from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Badge } from "./components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";
import PageHeader from "./components/layout/PageHeader";

const ROLES = ["MEMBER", "LEAD"];

function readableError(error, fallback) {
  const messages = {
    400: "The request was invalid.",
    403: "You do not have permission to do that.",
    404: "The group or project was not found.",
    409: "That change conflicts with the current group.",
  };
  const generic =
    !error?.message || /^Request failed \(\d+\)/.test(error.message);
  if (messages[error?.status] && generic) return messages[error.status];
  return error?.message || fallback;
}

function roleLabel(role) {
  return role ? role.toLowerCase() : "";
}

export default function GroupsPanel({ api, user, projects, projectRoles }) {
  const isAdmin = user.roles.includes("admin");
  const canCreate = isAdmin || user.roles.includes("lead");
  const [groups, setGroups] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [members, setMembers] = useState(null);
  const [linkedProjects, setLinkedProjects] = useState(null);
  const [listError, setListError] = useState("");
  const [membersError, setMembersError] = useState("");
  const [projectsError, setProjectsError] = useState("");
  const [actionError, setActionError] = useState("");
  const [busy, setBusy] = useState(false);
  const [groupName, setGroupName] = useState("");
  const [memberUsername, setMemberUsername] = useState("");
  const [memberRole, setMemberRole] = useState("MEMBER");
  const [linkProjectId, setLinkProjectId] = useState("");
  const [linkRole, setLinkRole] = useState("MEMBER");

  const selectedGroup = groups?.find((group) => group.id === selectedId);
  const groupRole = selectedGroup?.role;
  const canSeeMembers = isAdmin || groupRole != null;
  const canManageGroup = isAdmin || groupRole === "LEAD";

  const loadGroups = useCallback(
    async (preferredId = null) => {
      const next = await api("/groups");
      setGroups(next);
      setSelectedId((current) => {
        if (
          preferredId != null &&
          next.some((group) => group.id === preferredId)
        )
          return preferredId;
        return next.some((group) => group.id === current)
          ? current
          : (next[0]?.id ?? null);
      });
      return next;
    },
    [api],
  );

  const loadMembers = useCallback(async () => {
    if (selectedId == null) return;
    setMembersError("");
    try {
      setMembers(await api(`/groups/${selectedId}/members`));
    } catch (error) {
      setMembersError(readableError(error, "Unable to load group members."));
    }
  }, [api, selectedId]);

  const loadProjects = useCallback(async () => {
    if (selectedId == null) return;
    setProjectsError("");
    try {
      setLinkedProjects(await api(`/groups/${selectedId}/projects`));
    } catch (error) {
      setProjectsError(readableError(error, "Unable to load linked projects."));
    }
  }, [api, selectedId]);

  useEffect(() => {
    loadGroups().catch((error) => {
      setListError(readableError(error, "Unable to load groups."));
      setGroups([]);
    });
  }, [loadGroups]);

  useEffect(() => {
    setMembers(null);
    setLinkedProjects(null);
    setMembersError("");
    setProjectsError("");
    if (selectedId == null) return;
    void loadMembers();
    void loadProjects();
  }, [loadMembers, loadProjects, selectedId]);

  async function run(action) {
    setBusy(true);
    setActionError("");
    try {
      await action();
    } catch (error) {
      setActionError(
        readableError(error, "The request could not be completed."),
      );
    } finally {
      setBusy(false);
    }
  }

  function createGroup(event) {
    event.preventDefault();
    void run(async () => {
      const created = await api("/groups", {
        method: "POST",
        body: JSON.stringify({ name: groupName.trim() }),
      });
      setGroupName("");
      await loadGroups(created.id);
    });
  }

  function addMember(event) {
    event.preventDefault();
    void run(async () => {
      await api(`/groups/${selectedId}/members`, {
        method: "POST",
        body: JSON.stringify({
          username: memberUsername.trim(),
          role: memberRole,
        }),
      });
      setMemberUsername("");
      setMemberRole("MEMBER");
      await loadMembers();
    });
  }

  function changeMemberRole(username, role) {
    void run(() =>
      api(`/groups/${selectedId}/members/${encodeURIComponent(username)}`, {
        method: "PUT",
        body: JSON.stringify({ role }),
      }).then(loadMembers),
    );
  }

  function removeMember(username) {
    void run(() =>
      api(`/groups/${selectedId}/members/${encodeURIComponent(username)}`, {
        method: "DELETE",
      }).then(loadMembers),
    );
  }

  function linkProject(event) {
    event.preventDefault();
    void run(async () => {
      await api(`/groups/${selectedId}/projects`, {
        method: "POST",
        body: JSON.stringify({
          projectId: Number(linkProjectId),
          role: linkRole,
        }),
      });
      setLinkProjectId("");
      setLinkRole("MEMBER");
      await loadProjects();
    });
  }

  function changeLinkRole(projectId, role) {
    void run(() =>
      api(`/groups/${selectedId}/projects/${projectId}`, {
        method: "PUT",
        body: JSON.stringify({ role }),
      }).then(loadProjects),
    );
  }

  function unlinkProject(projectId) {
    void run(() =>
      api(`/groups/${selectedId}/projects/${projectId}`, {
        method: "DELETE",
      }).then(loadProjects),
    );
  }

  function deleteGroup() {
    if (!window.confirm(`Delete ${selectedGroup.name}? This cannot be undone.`))
      return;
    void run(async () => {
      await api(`/groups/${selectedId}`, { method: "DELETE" });
      setMembers(null);
      setLinkedProjects(null);
      await loadGroups();
    });
  }

  const linkedIds = new Set(
    (linkedProjects ?? []).map((project) => project.id),
  );
  const manageableProjects = (projects ?? []).filter((project) => {
    const role = projectRoles?.get(project.id) ?? project.effectiveRole;
    return !linkedIds.has(project.id) && (isAdmin || role === "LEAD");
  });

  return (
    <main id="groups" className="page-container">
      <PageHeader title="Groups" />
      {(listError || actionError) && (
        <div className="error-message" role="alert">
          {listError || actionError}
        </div>
      )}
      <p className="members-note">
        Groups are useful when several people need the same project access. For
        one person, add them directly to the project instead.
      </p>
      {canCreate && (
        <Card className="project-card">
          <CardHeader>
            <CardTitle>Create a group</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="group-add" onSubmit={createGroup}>
              <div className="form-field">
                <Label htmlFor="group-name">Group name</Label>
                <Input
                  id="group-name"
                  required
                  maxLength="100"
                  value={groupName}
                  onChange={(event) => setGroupName(event.target.value)}
                />
              </div>
              <Button type="submit" disabled={busy}>
                Create
              </Button>
            </form>
          </CardContent>
        </Card>
      )}
      {groups?.length === 0 ? (
        <Card className="project-card">
          <CardContent>
            <div className="empty-state">
              <Users size={28} aria-hidden="true" />
              <strong>No groups yet</strong>
            </div>
          </CardContent>
        </Card>
      ) : (
        <div className="groups-layout">
          <Card className="project-card">
            <CardHeader>
              <CardTitle>Groups</CardTitle>
            </CardHeader>
            <CardContent>
              {groups === null ? (
                <p className="loading-state" role="status">
                  Loading groups…
                </p>
              ) : (
                <ul className="groups-list">
                  {groups.map((group) => (
                    <li key={group.id}>
                      <button
                        type="button"
                        className="group-choice"
                        aria-current={
                          group.id === selectedId ? "true" : undefined
                        }
                        onClick={() => setSelectedId(group.id)}
                      >
                        <span>{group.name}</span>
                        {group.role && (
                          <Badge variant="outline">
                            Your role: {group.role}
                          </Badge>
                        )}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
          {selectedGroup && (
            <div className="group-detail">
              <Card className="project-card">
                <CardHeader>
                  <CardTitle>{selectedGroup.name}</CardTitle>
                  {canManageGroup && (
                    <Button
                      type="button"
                      onClick={deleteGroup}
                      disabled={busy}
                      className="w-fit"
                      variant="destructive"
                    >
                      Delete group
                    </Button>
                  )}
                </CardHeader>
                {canSeeMembers ? (
                  <CardContent>
                    <h3>Members</h3>
                    <p className="members-note">
                      The group role only controls who manages the group and
                      grants nothing on projects. Use a project link below to
                      give every group member shared project access.
                    </p>
                    {membersError && (
                      <div className="error-message" role="alert">
                        {membersError}
                      </div>
                    )}
                    {members?.length === 0 ? (
                      <div className="empty-state">
                        <strong>No members yet</strong>
                      </div>
                    ) : (
                      <ul className="members-list">
                        {members?.map((member) => (
                          <li className="member-row" key={member.username}>
                            <span className="member-name">
                              {member.username}
                              {member.username.toLowerCase() ===
                                user.username.toLowerCase() && " (you)"}
                            </span>
                            {canManageGroup && (
                              <div className="member-actions">
                                <label
                                  className="sr-only"
                                  htmlFor={`group-member-role-${member.username}`}
                                >
                                  Group role for {member.username}
                                </label>
                                <select
                                  id={`group-member-role-${member.username}`}
                                  value={member.role}
                                  disabled={busy}
                                  onChange={(event) =>
                                    changeMemberRole(
                                      member.username,
                                      event.target.value,
                                    )
                                  }
                                >
                                  {ROLES.map((role) => (
                                    <option key={role}>{role}</option>
                                  ))}
                                </select>
                                <Button
                                  type="button"
                                  variant="outline"
                                  aria-label={`Remove ${member.username} from group`}
                                  onClick={() => removeMember(member.username)}
                                  disabled={busy}
                                >
                                  Remove
                                </Button>
                              </div>
                            )}
                          </li>
                        ))}
                      </ul>
                    )}
                    {canManageGroup && (
                      <form className="member-add" onSubmit={addMember}>
                        <div className="form-field">
                          <Label htmlFor="group-member-username">
                            Username
                          </Label>
                          <Input
                            id="group-member-username"
                            required
                            value={memberUsername}
                            onChange={(event) =>
                              setMemberUsername(event.target.value)
                            }
                          />
                        </div>
                        <div className="form-field">
                          <Label htmlFor="group-new-member-role">Role</Label>
                          <select
                            id="group-new-member-role"
                            value={memberRole}
                            disabled={busy}
                            onChange={(event) =>
                              setMemberRole(event.target.value)
                            }
                          >
                            {ROLES.map((role) => (
                              <option key={role}>{role}</option>
                            ))}
                          </select>
                        </div>
                        <Button type="submit" disabled={busy}>
                          Add member
                        </Button>
                      </form>
                    )}
                  </CardContent>
                ) : (
                  <CardContent>
                    <h3>Members</h3>
                    <p className="members-note">
                      Members are visible to group members and admins.
                    </p>
                  </CardContent>
                )}
              </Card>
              <Card className="project-card">
                <CardHeader>
                  <CardTitle>Linked projects</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="members-note">
                    Project access applies to every member of the group. Group
                    managers do not automatically get project access. For one
                    person, add them directly to the project instead.
                  </p>
                  {projectsError && (
                    <div className="error-message" role="alert">
                      {projectsError}
                    </div>
                  )}
                  {linkedProjects?.length === 0 ? (
                    <div className="empty-state">
                      <strong>No linked projects yet</strong>
                    </div>
                  ) : (
                    <ul className="members-list">
                      {linkedProjects?.map((project) => {
                        const role =
                          projectRoles?.get(project.id) ??
                          project.effectiveRole;
                        const canManageLink = isAdmin || role === "LEAD";
                        return (
                          <li className="member-row" key={project.id}>
                            <span className="member-name">{project.name}</span>
                            <Badge variant="outline">
                              Project access: {roleLabel(project.role)}
                            </Badge>
                            {canManageLink && (
                              <div className="member-actions">
                                <label
                                  className="sr-only"
                                  htmlFor={`group-project-role-${project.id}`}
                                >
                                  Project access for group in {project.name}
                                </label>
                                <select
                                  id={`group-project-role-${project.id}`}
                                  value={project.role}
                                  disabled={busy}
                                  onChange={(event) =>
                                    changeLinkRole(
                                      project.id,
                                      event.target.value,
                                    )
                                  }
                                >
                                  {ROLES.map((link) => (
                                    <option key={link}>{link}</option>
                                  ))}
                                </select>
                                <Button
                                  type="button"
                                  variant="outline"
                                  onClick={() => unlinkProject(project.id)}
                                  disabled={busy}
                                >
                                  Unlink
                                </Button>
                              </div>
                            )}
                          </li>
                        );
                      })}
                    </ul>
                  )}
                  {(isAdmin || manageableProjects.length > 0) && (
                    <form className="member-add" onSubmit={linkProject}>
                      <div className="form-field">
                        <Label htmlFor="group-project-picker">Project</Label>
                        <select
                          id="group-project-picker"
                          required
                          value={linkProjectId}
                          disabled={busy || manageableProjects.length === 0}
                          onChange={(event) =>
                            setLinkProjectId(event.target.value)
                          }
                        >
                          <option value="">Select a project</option>
                          {manageableProjects.map((project) => (
                            <option key={project.id} value={project.id}>
                              {project.name}
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="form-field">
                        <Label htmlFor="group-link-role">
                          Project access for group
                        </Label>
                        <select
                          id="group-link-role"
                          value={linkRole}
                          disabled={busy}
                          onChange={(event) => setLinkRole(event.target.value)}
                        >
                          {ROLES.map((role) => (
                            <option key={role}>{role}</option>
                          ))}
                        </select>
                      </div>
                      <Button type="submit" disabled={busy || !linkProjectId}>
                        Link project
                      </Button>
                    </form>
                  )}
                </CardContent>
              </Card>
            </div>
          )}
        </div>
      )}
    </main>
  );
}
