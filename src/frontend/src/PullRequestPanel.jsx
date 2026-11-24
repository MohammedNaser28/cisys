/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { Save } from "lucide-react";
import { Button } from "./components/ui/button";
import { Label } from "./components/ui/label";
import { Card, CardContent } from "./components/ui/card";

/**
 * Pull request and merge request builds. When enabled, same-repository heads are discovered and
 * built automatically; fork heads are never fetched.
 */
export default function PullRequestPanel({ api }) {
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    api("/settings/pull-requests")
      .then(setDraft)
      .catch((e) => setError(e.message));
  }, [api]);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      setDraft(
        await api("/settings/pull-requests", {
          method: "PUT",
          body: JSON.stringify({ enabled: draft.enabled }),
        }),
      );
      setNotice("Pull request builds saved.");
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
        {!draft ? (
          !error && <p role="status">Loading pull request settings...</p>
        ) : (
          <form onSubmit={submit}>
            <div className="settings-form">
              <div className="form-field">
                <Label htmlFor="pull-requests-enabled">
                  Pull request builds
                </Label>
                <span className="private-choice gap-2">
                  <input
                    id="pull-requests-enabled"
                    type="checkbox"
                    checked={draft.enabled}
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, enabled: e.target.checked }))
                    }
                  />
                  <span>Build pull requests</span>
                </span>
              </div>
            </div>
            <div className="flex justify-end mt-4">
              <Button
                type="submit"
                size="icon"
                disabled={busy}
                title="Save pull request settings"
                aria-label="Save pull request settings"
              >
                <Save size={16} aria-hidden="true" />
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
