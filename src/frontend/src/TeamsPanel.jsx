/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THE ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS FILE CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Check,
  Eraser,
  KeyRound,
  Pencil,
  Plus,
  Trash2,
  Users,
  X,
} from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Badge } from "./components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./components/ui/select";

const ROLES = ["MEMBER", "LEAD"];

function message(t, error, fallbackKey) {
  if (error?.message && !/^Request failed \(\d+\)/.test(error.message))
    return error.message;
  const key = {
    403: "teams.errorForbidden",
    404: "teams.errorNotFound",
    409: "teams.errorLastLead",
  }[error?.status];
  return key ? t(key) : t(fallbackKey);
}

/** The Users-page section for team management. Tokens are write-only. */
export default function TeamsPanel({ api, user }) {
  const { t } = useTranslation();
  const isAdmin = user.roles.includes("admin");
  const canCreate = isAdmin || user.roles.includes("lead");
  const [teams, setTeams] = useState(null);
  const [memberLists, setMemberLists] = useState({});
  const [mode, setMode] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [newName, setNewName] = useState("");
  const [name, setName] = useState("");
  const [username, setUsername] = useState("");
  const [memberRole, setMemberRole] = useState("MEMBER");
  const [token, setToken] = useState("");
  const [validationProject, setValidationProject] = useState("");
  const [tokenStates, setTokenStates] = useState({});
  const [projectList, setProjectList] = useState(null);
  const [projectsState, setProjectsState] = useState("idle");
  const [projectsError, setProjectsError] = useState("");
  const teamsRequest = useRef(0);

  const loadTeams = useCallback(async () => {
    const request = ++teamsRequest.current;
    const next = await api("/teams");
    // A slow, older response must not replace a newer team list.
    if (request !== teamsRequest.current) return;
    setTeams(next);
    // The fresh list is authoritative for token state; a cached mutation result
    // must not outlive a reload where another admin may have changed the token.
    setTokenStates({});
  }, [api]);

  const loadProjects = useCallback(async () => {
    setProjectsState("loading");
    setProjectsError("");
    try {
      setProjectList(await api("/projects"));
      setProjectsState("ready");
    } catch (e) {
      setProjectList(null);
      setProjectsError(message(t, e, "teams.errorLoadProjects"));
      setProjectsState("error");
    }
  }, [api]);

  const loadMembers = useCallback(
    async (teamId) => {
      setMemberLists((current) => ({ ...current, [teamId]: null }));
      try {
        const members = await api(`/teams/${teamId}/members`);
        setMemberLists((current) => ({ ...current, [teamId]: members }));
      } catch (e) {
        setMemberLists((current) => {
          const next = { ...current };
          delete next[teamId];
          return next;
        });
        throw e;
      }
    },
    [api],
  );

  useEffect(() => {
    loadTeams().catch((e) => {
      setError(message(t, e, "teams.errorLoadTeams"));
      setTeams([]);
    });
  }, [loadTeams]);

  async function run(action) {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(message(t, e, "teams.errorRequestFailed"));
    } finally {
      setBusy(false);
    }
  }

  function canManage(team) {
    return isAdmin || team.role === "LEAD";
  }

  function memberCount(team) {
    if (Array.isArray(memberLists[team.id])) return memberLists[team.id].length;
    return typeof team.memberCount === "number" ? team.memberCount : null;
  }

  function tokenIsSet(team) {
    if (Object.hasOwn(tokenStates, team.id)) return !!tokenStates[team.id]?.set;
    return team.tokenSet === true;
  }

  function privateProjects(teamId) {
    if (!Array.isArray(projectList)) return [];
    return projectList.filter(
      (project) => project.teamId === teamId && project.privateRepository,
    );
  }

  function create(event) {
    event.preventDefault();
    void run(async () => {
      await api("/teams", {
        method: "POST",
        body: JSON.stringify({
          name: newName.trim(),
        }),
      });
      setNewName("");
      await loadTeams();
    });
  }

  function openEdit(team) {
    setMode({ teamId: team.id, kind: "edit" });
    setName(team.name);
    setUsername("");
    setMemberRole("MEMBER");
    // Always refetch: another LEAD may have changed the team since it was last opened.
    void run(() => loadMembers(team.id));
  }

  function openToken(team) {
    setMode({ teamId: team.id, kind: "token" });
    setToken("");
    setValidationProject("");
    if (projectsState === "idle" || projectsState === "error")
      void loadProjects();
  }

  function rename(event) {
    event.preventDefault();
    if (!mode) return;
    const teamId = mode.teamId;
    void run(async () => {
      await api(`/teams/${teamId}`, {
        method: "PUT",
        body: JSON.stringify({ name: name.trim() }),
      });
      setMode(null);
      await loadTeams();
    });
  }

  function addMember() {
    if (!mode || !username.trim()) return;
    const teamId = mode.teamId;
    void run(async () => {
      await api(`/teams/${teamId}/members`, {
        method: "POST",
        body: JSON.stringify({ username: username.trim(), role: memberRole }),
      });
      setUsername("");
      setMemberRole("MEMBER");
      await loadMembers(teamId);
    });
  }

  function changeRole(teamId, member, role) {
    void run(() =>
      api(`/teams/${teamId}/members/${encodeURIComponent(member.username)}`, {
        method: "PUT",
        body: JSON.stringify({ role }),
      }).then(() => loadMembers(teamId)),
    );
  }

  function removeMember(teamId, member) {
    void run(() =>
      api(`/teams/${teamId}/members/${encodeURIComponent(member.username)}`, {
        method: "DELETE",
      }).then(() => loadMembers(teamId)),
    );
  }

  function saveToken(event) {
    event.preventDefault();
    if (!mode) return;
    const teamId = mode.teamId;
    void run(async () => {
      const result = await api(`/teams/${teamId}/token`, {
        method: "PUT",
        body: JSON.stringify({
          token: token.trim(),
          projectId: validationProject ? Number(validationProject) : null,
        }),
      });
      setToken("");
      setTokenStates((current) => ({ ...current, [teamId]: result }));
    });
  }

  function clearToken() {
    if (!mode) return;
    const teamId = mode.teamId;
    void run(async () => {
      const result = await api(`/teams/${teamId}/token`, { method: "DELETE" });
      setToken("");
      setTokenStates((current) => ({ ...current, [teamId]: result }));
    });
  }

  function deleteTeam(team) {
    if (
      !window.confirm(
        t("teams.confirmDelete", { count: team.projectCount, name: team.name }),
      )
    )
      return;
    void run(async () => {
      await api(`/teams/${team.id}`, { method: "DELETE" });
      setMode((current) => (current?.teamId === team.id ? null : current));
      await loadTeams();
    });
  }

  function editRow(team) {
    const members = memberLists[team.id];
    return (
      <form
        className="user-row team-edit-row is-selected"
        key={team.id}
        onSubmit={rename}
        aria-label={t("teams.edit", { name: team.name })}
        data-selected="true"
      >
        <div className="user-edit col-span-full w-full">
          <div className="form-field">
            <Label htmlFor={`team-name-${team.id}`}>{t("teams.name")}</Label>
            <Input
              id={`team-name-${team.id}`}
              aria-label={t("teams.teamNameFor", { name: team.name })}
              required
              maxLength="100"
              autoComplete="off"
              spellCheck={false}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="members-list">
            {members === null ? (
              <p role="status">{t("teams.loadingMembers")}</p>
            ) : Array.isArray(members) ? (
              members.length === 0 ? (
                <p className="field-hint">{t("teams.noMembers")}</p>
              ) : (
                members.map((member) => (
                  <div className="member-row" key={member.username}>
                    <span className="member-name">{member.username}</span>
                    <Select
                      value={member.role}
                      disabled={busy}
                      onValueChange={(role) =>
                        changeRole(team.id, member, role)
                      }
                    >
                      <SelectTrigger
                        aria-label={t("teams.roleFor", {
                          username: member.username,
                        })}
                        className="w-full"
                      >
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {ROLES.map((role) => (
                          <SelectItem key={role} value={role}>
                            {t(
                              role === "MEMBER"
                                ? "teams.roleMember"
                                : "teams.roleLead",
                            )}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <div className="member-actions">
                      <Button
                        type="button"
                        size="icon"
                        className="h-10 w-10"
                        variant="outline"
                        disabled={busy}
                        title={t("teams.remove")}
                        aria-label={t("teams.removeMember", {
                          username: member.username,
                        })}
                        onClick={() => removeMember(team.id, member)}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </Button>
                    </div>
                  </div>
                ))
              )
            ) : (
              <p className="field-hint">{t("teams.errorLoadMembers")}</p>
            )}
          </div>
          {canManage(team) && (
            <div className="member-add">
              <Input
                aria-label={t("teams.username")}
                placeholder={t("teams.usernamePlaceholder")}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
              <Select value={memberRole} onValueChange={setMemberRole}>
                <SelectTrigger
                  aria-label={t("teams.newMemberRole")}
                  className="w-full"
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ROLES.map((role) => (
                    <SelectItem key={role} value={role}>
                      {t(
                        role === "MEMBER"
                          ? "teams.roleMember"
                          : "teams.roleLead",
                      )}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Button
                type="button"
                size="icon"
                className="h-10 w-10"
                disabled={busy || !username.trim()}
                title={t("teams.addMember")}
                aria-label={t("teams.addMember")}
                onClick={addMember}
              >
                <Plus size={16} aria-hidden="true" />
              </Button>
            </div>
          )}
          <div className="token-actions">
            <span className="field-hint">
              Member changes are saved immediately.
            </span>
            <div className="user-actions">
              <span aria-hidden="true" />
              <Button
                type="submit"
                size="icon"
                className="h-10 w-10"
                disabled={busy}
                title={t("teams.saveTeam")}
                aria-label={t("teams.save", { name: team.name })}
              >
                <Check size={16} aria-hidden="true" />
              </Button>
              <Button
                type="button"
                size="icon"
                variant="outline"
                className="h-10 w-10"
                disabled={busy}
                title={t("teams.cancel")}
                aria-label={t("teams.cancel")}
                onClick={() => setMode(null)}
              >
                <X size={16} aria-hidden="true" />
              </Button>
            </div>
          </div>
        </div>
      </form>
    );
  }

  function tokenRow(team) {
    const state = tokenStates[team.id];
    const options = privateProjects(team.id);
    return (
      <form
        className="user-row team-token-editor is-selected"
        key={team.id}
        onSubmit={saveToken}
        aria-label={t("teams.tokenFor", { name: team.name })}
        data-selected="true"
      >
        <div className="user-edit col-span-full w-full">
          <div className="team-token-row">
            <div className="form-field">
              <Label htmlFor={`team-token-${team.id}`}>
                {t("teams.tokenLabel")}
              </Label>
              <Input
                id={`team-token-${team.id}`}
                type="password"
                autoComplete="new-password"
                value={token}
                onChange={(e) => setToken(e.target.value)}
                placeholder={t("teams.tokenPlaceholder")}
              />
              <p className="field-hint">The token is never shown again.</p>
            </div>
            <div className="form-field">
              <Label htmlFor={`team-token-project-${team.id}`}>
                {t("teams.validateOptional")}
              </Label>
              <Select
                value={validationProject || "none"}
                disabled={busy || projectsState === "loading"}
                onValueChange={(value) =>
                  setValidationProject(value === "none" ? "" : value)
                }
              >
                <SelectTrigger
                  id={`team-token-project-${team.id}`}
                  aria-label={t("teams.validateAgainst")}
                  className="w-full"
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">
                    {t("teams.storeWithoutValidation")}
                  </SelectItem>
                  {options.map((project) => (
                    <SelectItem key={project.id} value={String(project.id)}>
                      {project.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {projectsState === "loading" && (
                <p className="field-hint" role="status">
                  {t("teams.loadingPrivateProjects")}
                </p>
              )}
              {projectsState === "error" && (
                <p className="field-hint" role="alert">
                  {projectsError} {t("teams.storeWithoutValidationHint")}
                </p>
              )}
              {projectsState === "ready" && options.length === 0 && (
                <p className="field-hint">{t("teams.noPrivateProjectsHint")}</p>
              )}
            </div>
          </div>
          <div className="token-actions">
            <div className="token-status">
              {state?.set && (
                <p className="success-message" role="status">
                  {t("teams.tokenSetNotice")}
                </p>
              )}
            </div>
            <div className="user-actions">
              <Button
                type="submit"
                size="icon"
                className="h-10 w-10"
                disabled={busy || !token.trim()}
                title={t("teams.saveToken")}
                aria-label={t("teams.saveToken")}
              >
                <Check size={16} aria-hidden="true" />
              </Button>
              <Button
                type="button"
                size="icon"
                variant="outline"
                className="h-10 w-10"
                disabled={busy}
                title={t("teams.clearToken")}
                aria-label={t("teams.clearToken")}
                onClick={clearToken}
              >
                <Eraser size={16} aria-hidden="true" />
              </Button>
              <Button
                type="button"
                size="icon"
                variant="outline"
                className="h-10 w-10"
                disabled={busy}
                title={t("teams.cancel")}
                aria-label={t("teams.cancel")}
                onClick={() => setMode(null)}
              >
                <X size={16} aria-hidden="true" />
              </Button>
            </div>
          </div>
        </div>
      </form>
    );
  }

  return (
    <section id="teams" aria-label={t("teams.title")}>
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      {canCreate && (
        <Card className="project-card">
          <CardHeader>
            <CardTitle>{t("teams.add")}</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="team-add" onSubmit={create}>
              <div className="form-field">
                <Label htmlFor="team-name">{t("teams.name")}</Label>
                <Input
                  id="team-name"
                  required
                  maxLength="100"
                  autoComplete="off"
                  spellCheck={false}
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                />
              </div>
              <Button
                type="submit"
                size="icon"
                className="h-10 w-10"
                disabled={busy}
                title={t("teams.addAction")}
                aria-label={t("teams.addAction")}
              >
                <Plus size={16} aria-hidden="true" />
              </Button>
            </form>
          </CardContent>
        </Card>
      )}
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Teams</CardTitle>
        </CardHeader>
        <CardContent>
          {teams === null ? (
            <p role="status">{t("teams.loading")}</p>
          ) : teams.length === 0 ? (
            <div className="empty-state">
              <Users size={28} aria-hidden="true" />
              <strong>{t("teams.empty")}</strong>
            </div>
          ) : (
            teams.map((team) => {
              if (mode?.teamId === team.id && mode.kind === "edit")
                return editRow(team);
              if (mode?.teamId === team.id && mode.kind === "token")
                return tokenRow(team);
              const count = memberCount(team);
              return (
                <div className="user-row team-row" key={team.id}>
                  <div className="project-name">
                    <strong>{team.name}</strong>
                  </div>
                  <div className="user-tags">
                    {count != null && (
                      <Badge variant="secondary">
                        {t("teams.members", { count })}
                      </Badge>
                    )}
                    <Badge variant="outline">
                      {t("teams.projects", { count: team.projectCount })}
                    </Badge>
                    {tokenIsSet(team) && (
                      <Badge variant="outline">{t("teams.tokenSet")}</Badge>
                    )}
                  </div>
                  <div className="user-actions">
                    {canManage(team) ? (
                      <Button
                        type="button"
                        size="icon"
                        variant="outline"
                        disabled={busy}
                        title={t("teams.edit", { name: team.name })}
                        aria-label={t("teams.edit", { name: team.name })}
                        onClick={() => openEdit(team)}
                      >
                        <Pencil size={16} aria-hidden="true" />
                      </Button>
                    ) : (
                      <span aria-hidden="true" />
                    )}
                    {canManage(team) ? (
                      <Button
                        type="button"
                        size="icon"
                        variant="outline"
                        disabled={busy}
                        title={t("teams.setOrClearToken")}
                        aria-label={t("teams.setOrClearTokenFor", {
                          name: team.name,
                        })}
                        onClick={() => openToken(team)}
                      >
                        <KeyRound size={16} aria-hidden="true" />
                      </Button>
                    ) : (
                      <span aria-hidden="true" />
                    )}
                    {canManage(team) ? (
                      <Button
                        type="button"
                        size="icon"
                        variant="outline"
                        disabled={busy}
                        title={t("teams.delete", { name: team.name })}
                        aria-label={t("teams.delete", { name: team.name })}
                        onClick={() => deleteTeam(team)}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </Button>
                    ) : (
                      <span aria-hidden="true" />
                    )}
                  </div>
                </div>
              );
            })
          )}
        </CardContent>
      </Card>
    </section>
  );
}
