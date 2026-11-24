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
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./components/ui/select";
import { Card, CardContent } from "./components/ui/card";

/**
 * Automatic builds: the timer, its granularity and the branches it watches. Global, like the rest
 * of Settings; a branch is only watched when its tip has .cisys/workflows/ci.yml.
 */
export default function PollingPanel({ api }) {
  const [draft, setDraft] = useState(null);
  const [savedInterval, setSavedInterval] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    api("/settings/polling")
      .then((loaded) => {
        setDraft(loaded);
        setSavedInterval(loaded.intervalSeconds);
      })
      .catch((e) => setError(e.message));
  }, [api]);

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
      const saved = await api("/settings/polling", {
        method: "PUT",
        body: JSON.stringify({
          enabled: draft.enabled,
          granularity: draft.granularity,
          scope: draft.scope,
          // Null keeps cisys.poll.every in charge; a value only when the admin changed it.
          intervalSeconds:
            String(draft.intervalSeconds) === String(savedInterval)
              ? null
              : Number(draft.intervalSeconds),
          mirrorManualBuilds: draft.mirrorManualBuilds,
        }),
      });
      setDraft(saved);
      setSavedInterval(saved.intervalSeconds);
      setNotice("Automatic builds saved.");
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
          !error && <p role="status">Loading automatic build settings...</p>
        ) : (
          <form onSubmit={submit}>
            <div className="settings-form">
              <div className="form-field">
                <Label htmlFor="polling-enabled">Automatic builds</Label>
                <span className="private-choice gap-2">
                  <input
                    id="polling-enabled"
                    type="checkbox"
                    checked={draft.enabled}
                    onChange={(e) => change("enabled", e.target.checked)}
                  />
                  <span>Build new commits on a timer</span>
                </span>
              </div>
              <div className="form-field">
                <Label htmlFor="polling-granularity">Granularity</Label>
                <Select
                  value={draft.granularity}
                  onValueChange={(value) => change("granularity", value)}
                >
                  <SelectTrigger
                    id="polling-granularity"
                    className="h-10 w-full"
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="PER_COMMIT">
                      One build per commit
                    </SelectItem>
                    <SelectItem value="TIP">Tip only</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="form-field">
                <Label htmlFor="polling-scope">Branches</Label>
                <Select
                  value={draft.scope}
                  onValueChange={(value) => change("scope", value)}
                >
                  <SelectTrigger id="polling-scope" className="h-10 w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="WORKFLOW">
                      All branches with .cisys/workflows/ci.yml
                    </SelectItem>
                    <SelectItem value="MAIN_ONLY">
                      main or master only
                    </SelectItem>
                  </SelectContent>
                </Select>
                <span className="file-help">
                  A branch is watched only when its tip has the workflow file.
                </span>
              </div>
              <div className="form-field">
                <Label htmlFor="polling-interval">
                  Poll interval (seconds)
                </Label>
                <Input
                  id="polling-interval"
                  type="number"
                  min={5}
                  max={86400}
                  step="1"
                  required
                  value={draft.intervalSeconds}
                  onChange={(e) => change("intervalSeconds", e.target.value)}
                />
              </div>
              <div className="form-field">
                <Label htmlFor="polling-mirror-manual">Manual builds</Label>
                <span className="private-choice gap-2">
                  <input
                    id="polling-mirror-manual"
                    type="checkbox"
                    checked={Boolean(draft.mirrorManualBuilds)}
                    onChange={(e) =>
                      change("mirrorManualBuilds", e.target.checked)
                    }
                  />
                  <span>Check out from the repository mirror</span>
                </span>
                <span className="file-help">
                  Manual builds and re-runs fetch into the mirror first instead
                  of cloning the repository.
                </span>
              </div>
            </div>
            <div className="flex justify-end mt-4">
              <Button
                type="submit"
                size="icon"
                disabled={busy}
                title="Save automatic build settings"
                aria-label="Save automatic build settings"
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
