/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { Check, Globe, Pencil, Plus, Trash2, X } from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Badge } from "./components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";

// Matches NetworkAllowlist.ANY: every public destination.
const ANY = "*";

export default function NetworkPanel({ api }) {
  const [hosts, setHosts] = useState(null);
  const [templates, setTemplates] = useState([]);
  const [name, setName] = useState("");
  const [host, setHost] = useState("");
  const [editing, setEditing] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    Promise.all([api("/network-hosts"), api("/network-hosts/templates")])
      .then(([h, t]) => {
        setHosts(h);
        setTemplates(t);
      })
      .catch((e) => setError(e.message));
  }, [api]);

  async function run(action) {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  const reload = async () => setHosts(await api("/network-hosts"));

  function add(event) {
    event.preventDefault();
    void run(async () => {
      await api("/network-hosts", {
        method: "POST",
        body: JSON.stringify({ name, host }),
      });
      setName("");
      setHost("");
      await reload();
    });
  }

  function addTemplate(template) {
    void run(async () =>
      setHosts(
        await api(`/network-hosts/templates/${template.id}`, {
          method: "POST",
        }),
      ),
    );
  }

  function save(event) {
    event.preventDefault();
    void run(async () => {
      await api(`/network-hosts/${editing.id}`, {
        method: "PUT",
        body: JSON.stringify({ name: editing.name, host: editing.host }),
      });
      setEditing(null);
      await reload();
    });
  }

  function remove(h) {
    if (!window.confirm(`Remove ${h.host}?`)) return;
    void run(async () => {
      await api(`/network-hosts/${h.id}`, { method: "DELETE" });
      await reload();
    });
  }

  const allowed = new Set((hosts || []).map((h) => h.host));

  return (
    <>
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Add a host</CardTitle>
        </CardHeader>
        <CardContent>
          <form className="project-grid project-add" onSubmit={add}>
            <div className="form-field">
              <Label htmlFor="network-name">Name</Label>
              <Input
                id="network-name"
                required
                maxLength="100"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="npm"
              />
            </div>
            <div className="form-field">
              <Label htmlFor="network-host">Host</Label>
              <Input
                id="network-host"
                required
                maxLength="253"
                spellCheck={false}
                value={host}
                onChange={(e) => setHost(e.target.value)}
                placeholder="registry.npmjs.org"
              />
            </div>
            <span />
            <Button
              type="submit"
              size="icon"
              className="justify-self-end"
              disabled={busy}
              title="Add host"
              aria-label="Add host"
            >
              <Plus size={16} aria-hidden="true" />
            </Button>
          </form>
          {templates.length > 0 && (
            <div className="network-templates">
              <Label>Templates</Label>
              <div className="network-template-list">
                {templates.map((t) => (
                  <Button
                    key={t.id}
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={busy || t.hosts.every((h) => allowed.has(h))}
                    title={t.hosts.join(", ")}
                    onClick={() => addTemplate(t)}
                  >
                    <Plus size={16} aria-hidden="true" />
                    {t.name}
                  </Button>
                ))}
              </div>
            </div>
          )}
        </CardContent>
      </Card>
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Allowed hosts</CardTitle>
        </CardHeader>
        <CardContent>
          {hosts === null ? (
            !error && <p role="status">Loading allowed hosts…</p>
          ) : hosts.length === 0 ? (
            <div className="empty-state">
              <Globe size={28} aria-hidden="true" />
              <strong>No network for build steps</strong>
            </div>
          ) : (
            hosts.map((h) =>
              editing?.id === h.id ? (
                <form
                  className="project-grid project-row"
                  key={h.id}
                  onSubmit={save}
                >
                  <Input
                    aria-label="Name"
                    required
                    maxLength="100"
                    value={editing.name}
                    onChange={(e) =>
                      setEditing({ ...editing, name: e.target.value })
                    }
                  />
                  <Input
                    aria-label="Host"
                    required
                    maxLength="253"
                    spellCheck={false}
                    value={editing.host}
                    onChange={(e) =>
                      setEditing({ ...editing, host: e.target.value })
                    }
                  />
                  <span />
                  <div className="network-actions">
                    <Button
                      type="submit"
                      size="icon"
                      disabled={busy}
                      title="Save host"
                      aria-label={`Save ${h.host}`}
                    >
                      <Check size={16} aria-hidden="true" />
                    </Button>
                    <Button
                      type="button"
                      size="icon"
                      variant="outline"
                      title="Cancel"
                      aria-label="Cancel"
                      onClick={() => setEditing(null)}
                    >
                      <X size={16} aria-hidden="true" />
                    </Button>
                  </div>
                </form>
              ) : (
                <div className="project-grid project-row" key={h.id}>
                  <strong className="project-name">{h.name}</strong>
                  <p className="project-url">{h.host}</p>
                  {h.host === ANY ? (
                    <Badge
                      variant="outline"
                      className="justify-self-start text-red-600 dark:text-red-400"
                    >
                      Any public host
                    </Badge>
                  ) : (
                    <span />
                  )}
                  <div className="network-actions">
                    <Button
                      type="button"
                      size="icon"
                      variant="outline"
                      disabled={busy}
                      title="Edit host"
                      aria-label={`Edit ${h.host}`}
                      onClick={() =>
                        setEditing({ id: h.id, name: h.name, host: h.host })
                      }
                    >
                      <Pencil size={16} aria-hidden="true" />
                    </Button>
                    <Button
                      type="button"
                      size="icon"
                      disabled={busy}
                      title="Remove host"
                      aria-label={`Remove ${h.host}`}
                      onClick={() => remove(h)}
                    >
                      <Trash2 size={16} aria-hidden="true" />
                    </Button>
                  </div>
                </div>
              ),
            )
          )}
        </CardContent>
      </Card>
    </>
  );
}
