/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from "react";
import { RotateCw, Trash2, Users, UserPlus } from "lucide-react";
import { Button } from "./components/ui/button";
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
import ConfirmDialog from "./components/layout/ConfirmDialog";
import { cloneMap, toMap } from "./member-diff";

const ROLES = ["MEMBER", "LEAD"];

const key = (username) => username.toLowerCase();

/**
 * Team management for one project, shown on the Team tab of the project's settings page.
 * Edits are staged locally and applied by the page's Save button against the
 * project team endpoints; the backend remains the authority — it re-checks every
 * rule that protects the last LEAD, the project creator and project authorization.
 */
export default function ProjectMembers({
  original, // server members or null while loading: [{username, role}]
  draft, // Map(lowercase -> {username, role}), the desired end state
  users, // usernames that can be added, or null while loading
  onDraftChange, // (nextDraft: Map) => void
  loadError, // string | "" — the list itself failed to load
  user,
  isAdmin,
  busy, // a save is in flight
  onRetry, // () => void — re-fetch members after a load failure
}) {
  const [name, setName] = useState("");
  const [role, setRole] = useState("MEMBER");
  const [addError, setAddError] = useState("");
  // The server member whose removal awaits confirmation in the pop-up, or null.
  const [confirmRemove, setConfirmRemove] = useState(null);

  const originalMap = toMap(original ?? []);
  const selfKey = key(user?.username ?? "");
  const me = draft?.get(selfKey);
  // A failed team-list load makes the team read-only: the UI cannot know the baseline.
  const canManage = !loadError && (isAdmin || me?.role === "LEAD");
  const readOnly = original !== null && draft !== null && !canManage;

  // Rows: original members in server order first, then staged additions appended.
  const rows = [];
  for (const m of original ?? []) {
    const k = key(m.username);
    const entry = draft?.get(k);
    if (!draft || entry) {
      rows.push({ username: m.username, role: entry?.role ?? m.role });
    }
  }
  if (draft) {
    for (const [k, entry] of draft) {
      if (!originalMap.has(k)) {
        rows.push({ username: entry.username, role: entry.role });
      }
    }
  }

  // Users not yet on the (staged) team, in the order the Users page lists them.
  const available = (users ?? []).filter((u) => !draft?.has(key(u)));

  function changeRole(row, nextRole) {
    if (row.role === nextRole || !canManage || busy) return;
    const next = cloneMap(draft);
    next.set(key(row.username), { username: row.username, role: nextRole });
    onDraftChange?.(next);
  }

  function remove(row) {
    if (!canManage || busy) return;
    const k = key(row.username);
    if (draft.has(k) && !originalMap.has(k)) {
      // Staged addition: drop it from the plan entirely.
      dropFromDraft(row);
    } else {
      // Server member: ask first, then mark them as pending removal.
      setConfirmRemove(row);
    }
  }

  function dropFromDraft(row) {
    const next = cloneMap(draft);
    next.delete(key(row.username));
    onDraftChange?.(next);
  }

  function addPending(event) {
    event.preventDefault();
    if (!draft || !canManage || busy) return;
    const username = name.trim();
    if (!username) return setAddError("Select a user.");
    const k = key(username);
    if (draft.has(k)) {
      setAddError(`'${username}' is already a member of this project.`);
      return;
    }
    const next = cloneMap(draft);
    next.set(k, { username, role });
    onDraftChange?.(next);
    setName("");
    setRole("MEMBER");
    setAddError("");
  }

  return (
    <Card className="project-card">
      <CardHeader>
        <CardTitle>Team</CardTitle>
      </CardHeader>
      <CardContent>
        {loadError && (
          <div className="error-message" role="alert">
            <span className="block">{loadError}</span>
            {onRetry && (
              <Button
                type="button"
                variant="outline"
                size="icon"
                className="mt-3"
                title="Retry"
                aria-label="Retry"
                onClick={onRetry}
              >
                <RotateCw size={16} aria-hidden="true" />
              </Button>
            )}
          </div>
        )}
        {original === null && !loadError ? (
          <p className="loading-state" role="status">
            Loading members…
          </p>
        ) : loadError ? null : rows.length === 0 ? (
          <div className="empty-state">
            <Users size={28} aria-hidden="true" />
            <strong>No members yet</strong>
            {canManage && <p>Add a user below.</p>}
          </div>
        ) : (
          <ul className="members-list">
            {rows.map((row) => {
              return (
                <li className="member-row" key={row.username}>
                  <span className="member-name">{row.username}</span>
                  <Badge variant="outline" className="justify-self-start">
                    {row.role.toLowerCase()}
                  </Badge>
                  {canManage ? (
                    <div className="member-actions">
                      <Select
                        value={row.role}
                        disabled={busy}
                        onValueChange={(value) => changeRole(row, value)}
                      >
                        <SelectTrigger
                          aria-label={`Role for ${row.username}`}
                          className="w-full"
                        >
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {ROLES.map((r) => (
                            <SelectItem key={r} value={r}>
                              {r.toLowerCase()}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <Button
                        type="button"
                        variant="outline"
                        size="icon"
                        disabled={busy}
                        title={`Remove ${row.username}`}
                        aria-label={`Remove ${row.username} from the project`}
                        onClick={() => remove(row)}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </Button>
                    </div>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
        {canManage && (
          <form className="member-add" onSubmit={addPending}>
            <div className="form-field">
              <Label htmlFor="member-username">User</Label>
              <Select
                value={name || undefined}
                disabled={busy || available.length === 0}
                onValueChange={(value) => {
                  setName(value);
                  setAddError("");
                }}
              >
                <SelectTrigger id="member-username" className="w-full">
                  <SelectValue
                    placeholder={
                      users === null
                        ? "Loading users…"
                        : available.length === 0
                          ? "No users available"
                          : "Select a user"
                    }
                  />
                </SelectTrigger>
                <SelectContent>
                  {available.map((u) => (
                    <SelectItem key={u} value={u}>
                      {u}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {addError && (
                <p className="member-add-error" role="alert">
                  {addError}
                </p>
              )}
            </div>
            <div className="form-field">
              <Label htmlFor="member-role">Role</Label>
              <Select value={role} disabled={busy} onValueChange={setRole}>
                <SelectTrigger id="member-role" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ROLES.map((r) => (
                    <SelectItem key={r} value={r}>
                      {r.toLowerCase()}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <Button
              type="submit"
              size="icon"
              disabled={busy || !name.trim()}
              title="Add member"
              aria-label="Add member"
            >
              <UserPlus size={16} aria-hidden="true" />
            </Button>
          </form>
        )}
        {readOnly && !loadError && (
          <p className="members-note">
            Only the project&apos;s leads and admins can change the team.
          </p>
        )}
      </CardContent>
      <ConfirmDialog
        open={confirmRemove !== null}
        title="Remove"
        message={
          confirmRemove
            ? `${confirmRemove.username} (${confirmRemove.role.toLowerCase()})`
            : ""
        }
        confirmLabel="Remove"
        onConfirm={() => {
          dropFromDraft(confirmRemove);
          setConfirmRemove(null);
        }}
        onCancel={() => setConfirmRemove(null)}
      />
    </Card>
  );
}
