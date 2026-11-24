/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useState } from "react";
import { Save, Trash2 } from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Textarea } from "./components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./components/ui/select";
import { Card, CardContent } from "./components/ui/card";

const EMPTY = { type: "", className: "", variables: "" };

/**
 * The report types an admin configured: the key used under a workflow's reports, the installed
 * Java class that runs it and its variables, one key=value per line, such as the path to orangu.
 */
export default function ReportsPanel({ api }) {
  const [overview, setOverview] = useState(null);
  const [draft, setDraft] = useState(EMPTY);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(
    () =>
      api("/report-plugins")
        .then(setOverview)
        .catch((e) => setError(e.message)),
    [api],
  );

  useEffect(() => {
    load();
  }, [load]);

  function change(key, value) {
    setDraft((d) => ({ ...d, [key]: value }));
    setError("");
    setNotice("");
  }

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await api(`/report-plugins/${encodeURIComponent(draft.type)}`, {
        method: "PUT",
        body: JSON.stringify({
          className: draft.className,
          variables: draft.variables,
        }),
      });
      setNotice(`Report type ${draft.type} saved.`);
      setDraft(EMPTY);
      await load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function remove(type) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await api(`/report-plugins/${encodeURIComponent(type)}`, {
        method: "DELETE",
      });
      setNotice(`Report type ${type} removed.`);
      await load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="project-card">
      <CardContent className="pt-6">
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
        {!overview ? (
          !error && <p role="status">Loading report types...</p>
        ) : (
          <>
            {overview.plugins.length === 0 ? (
              <p>No report types are configured.</p>
            ) : (
              <ul className="report-plugins">
                {overview.plugins.map((p) => (
                  <li key={p.type} className="flex items-center gap-2">
                    <Button
                      type="button"
                      variant="ghost"
                      onClick={() => setDraft(p)}
                    >
                      {p.type}
                    </Button>
                    <span className="file-help">{p.className}</span>
                    <Button
                      type="button"
                      size="icon"
                      variant="ghost"
                      disabled={busy}
                      title={`Remove report type ${p.type}`}
                      aria-label={`Remove report type ${p.type}`}
                      onClick={() => remove(p.type)}
                    >
                      <Trash2 size={16} aria-hidden="true" />
                    </Button>
                  </li>
                ))}
              </ul>
            )}
            <form onSubmit={submit}>
              <div className="settings-form">
                <div className="form-field">
                  <Label htmlFor="report-type">Report type</Label>
                  <Input
                    id="report-type"
                    required
                    maxLength={50}
                    pattern="[a-z][a-z0-9\-]*"
                    value={draft.type}
                    onChange={(e) => change("type", e.target.value)}
                  />
                  <span className="file-help">
                    The key under reports in a workflow, such as orangu.
                  </span>
                </div>
                <div className="form-field">
                  <Label htmlFor="report-class">Class</Label>
                  <Select
                    value={draft.className}
                    onValueChange={(value) => change("className", value)}
                  >
                    <SelectTrigger id="report-class" className="h-10 w-full">
                      <SelectValue placeholder="Choose a class" />
                    </SelectTrigger>
                    <SelectContent>
                      {overview.classes.map((c) => (
                        <SelectItem key={c} value={c}>
                          {c}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="form-field">
                  <Label htmlFor="report-variables">Variables</Label>
                  <Textarea
                    id="report-variables"
                    maxLength={4096}
                    placeholder="path=/usr/local/bin/orangu"
                    value={draft.variables}
                    onChange={(e) => change("variables", e.target.value)}
                  />
                  <span className="file-help">One key=value per line.</span>
                </div>
              </div>
              <div className="flex justify-end mt-4">
                <Button
                  type="submit"
                  size="icon"
                  className="h-10 w-10"
                  disabled={busy || !draft.className}
                  title="Save report type"
                  aria-label="Save report type"
                >
                  <Save size={16} aria-hidden="true" />
                </Button>
              </div>
            </form>
          </>
        )}
      </CardContent>
    </Card>
  );
}
