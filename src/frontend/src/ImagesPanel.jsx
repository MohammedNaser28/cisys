/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { Container, Download, RefreshCw, Save, Trash2 } from "lucide-react";
import { Button } from "./components/ui/button";
import { Spinner } from "./components/ui/spinner";
import { Badge } from "./components/ui/badge";
import { Input } from "./components/ui/input";
import { Card, CardHeader, CardTitle, CardContent } from "./components/ui/card";
import PageHeader from "./components/layout/PageHeader";

export default function ImagesPanel({ api, onSaved }) {
  const [local, setLocal] = useState([]);
  const [selected, setSelected] = useState(new Set());
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pullName, setPullName] = useState("");
  const [working, setWorking] = useState("");

  async function load() {
    setLoading(true);
    setError("");
    const allowed = await api("/images").catch(() => []);
    setSelected(new Set(allowed));
    try {
      setLocal(await api("/images/local"));
    } catch (e) {
      setLocal([]);
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    void load();
  }, []);

  function toggle(name) {
    setNotice("");
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  }
  async function save() {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const saved = await api("/images", {
        method: "PUT",
        body: JSON.stringify({ images: [...selected] }),
      });
      setSelected(new Set(saved));
      onSaved?.(saved);
      setNotice("Allowed images saved.");
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function pull(event, image) {
    event?.preventDefault();
    const name = image || pullName.trim();
    setWorking(image ? "pull:" + image : "pull");
    setError("");
    setNotice("");
    try {
      setLocal(
        await api("/images/local", {
          method: "POST",
          body: JSON.stringify({ name }),
        }),
      );
      if (!image) setPullName("");
      setNotice(
        selected.has(name)
          ? `Pulled ${name}.`
          : `Pulled ${name}. Allow it below and save to use it for builds.`,
      );
    } catch (e) {
      setError(e.message);
    } finally {
      setWorking("");
    }
  }
  async function remove(name) {
    if (
      !window.confirm(
        `Remove ${name} from the backend host? It will also be removed from the allowed images.`,
      )
    )
      return;
    setWorking(name);
    setError("");
    setNotice("");
    try {
      setLocal(
        await api("/images/local?name=" + encodeURIComponent(name), {
          method: "DELETE",
        }),
      );
      setSelected((s) => {
        const next = new Set(s);
        next.delete(name);
        return next;
      });
      onSaved?.(await api("/images"));
      setNotice(`Removed ${name}.`);
    } catch (e) {
      setError(e.message);
    } finally {
      setWorking("");
    }
  }

  const localNames = new Set(local.map((i) => i.name));
  const missing = [...selected].filter((name) => !localNames.has(name)).sort();
  const row = (name, details) => (
    <div className="image-row" key={name}>
      <input
        id={`allow-${name}`}
        type="checkbox"
        checked={selected.has(name)}
        onChange={() => toggle(name)}
      />
      <label htmlFor={`allow-${name}`} className="image-name">
        <code>{name}</code>
      </label>
      {details}
    </div>
  );

  return (
    <main className="page-container">
      <PageHeader
        title="Images"
        actions={<Badge variant="outline">{selected.size} allowed</Badge>}
      />
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      {notice && (
        <div className="success-message" role="status">
          {notice}
        </div>
      )}
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Pull an image</CardTitle>
        </CardHeader>
        <CardContent>
          <form className="image-pull" onSubmit={pull}>
            <div className="form-field">
              <Input
                id="pull-name"
                aria-label="Image to pull"
                required
                maxLength="201"
                pattern="[A-Za-z0-9][A-Za-z0-9_./:@\-]*"
                value={pullName}
                onChange={(e) => setPullName(e.target.value)}
                placeholder="docker.io/library/alpine:3.23"
              />
            </div>
            <Button
              type="submit"
              size="icon"
              className="h-10 w-10 justify-self-end"
              disabled={!!working || busy}
              title={working === "pull" ? "Pulling…" : "Pull image"}
              aria-label={working === "pull" ? "Pulling…" : "Pull image"}
            >
              {working === "pull" ? (
                <Spinner />
              ) : (
                <Download size={16} aria-hidden="true" />
              )}
            </Button>
          </form>
        </CardContent>
      </Card>
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Local images</CardTitle>
        </CardHeader>
        <CardContent>
          {loading ? (
            <p role="status">Loading images…</p>
          ) : local.length === 0 ? (
            <div className="empty-state">
              <Container size={28} aria-hidden="true" />
              <strong>No local images</strong>
              <p>Pull an image above to get started.</p>
            </div>
          ) : (
            <div className="image-list">
              <div className="image-row image-head" aria-hidden="true">
                <span />
                <span>Image</span>
                <span>ID</span>
                <span>Size</span>
                <span>Created</span>
                <span />
              </div>
              {local.map((i) =>
                row(
                  i.name,
                  <>
                    <span className="image-meta">{i.id.slice(0, 12)}</span>
                    <span className="image-meta">{i.size}</span>
                    <span className="image-meta">{i.created}</span>
                    <Button
                      type="button"
                      variant="outline"
                      size="icon"
                      className="h-10 w-10"
                      disabled={!!working}
                      onClick={() => void remove(i.name)}
                      title={working === i.name ? "Removing…" : "Remove image"}
                      aria-label={
                        working === i.name
                          ? `Removing ${i.name}`
                          : `Remove ${i.name}`
                      }
                    >
                      {working === i.name ? (
                        <Spinner />
                      ) : (
                        <Trash2 size={16} aria-hidden="true" />
                      )}
                    </Button>
                  </>,
                ),
              )}
            </div>
          )}
          {missing.length > 0 && (
            <div className="image-list image-missing">
              <p>
                <strong>Allowed but not present locally.</strong> Builds with
                these images will fail until they are pulled. Clear a box to
                remove it.
              </p>
              {missing.map((name) =>
                row(
                  name,
                  <>
                    <span />
                    <span />
                    <span />
                    <Button
                      type="button"
                      variant="outline"
                      size="icon"
                      className="h-10 w-10"
                      disabled={!!working}
                      onClick={() => void pull(null, name)}
                      title={
                        working === "pull:" + name ? "Pulling…" : "Pull image"
                      }
                      aria-label={
                        working === "pull:" + name
                          ? `Pulling ${name}`
                          : `Pull ${name}`
                      }
                    >
                      {working === "pull:" + name ? (
                        <Spinner />
                      ) : (
                        <Download size={16} aria-hidden="true" />
                      )}
                    </Button>
                  </>,
                ),
              )}
            </div>
          )}
          <div className="image-actions">
            <Button
              type="button"
              size="icon"
              className="h-10 w-10"
              onClick={save}
              disabled={busy || loading || !!working}
              title="Save allowed images"
              aria-label="Save allowed images"
            >
              <Save size={16} aria-hidden="true" />
            </Button>
            <Button
              type="button"
              variant="outline"
              size="icon"
              className="h-10 w-10"
              onClick={() => {
                setNotice("");
                void load();
              }}
              disabled={busy || loading}
              title="Reload images"
              aria-label="Reload images"
            >
              <RefreshCw size={16} aria-hidden="true" />
            </Button>
          </div>
        </CardContent>
      </Card>
    </main>
  );
}
