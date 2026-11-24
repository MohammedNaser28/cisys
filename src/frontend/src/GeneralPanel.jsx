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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./components/ui/select";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";

/** Settings that are not about builds, such as who may write articles. */
export default function GeneralPanel({ api }) {
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    api("/settings/general")
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
        await api("/settings/general", {
          method: "PUT",
          body: JSON.stringify({ articleEditors: draft.articleEditors }),
        }),
      );
      setNotice("Article settings saved.");
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="project-card">
      <CardHeader>
        <CardTitle>Articles</CardTitle>
      </CardHeader>
      <CardContent>
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
          !error && <p role="status">Loading article settings...</p>
        ) : (
          <form onSubmit={submit}>
            <div className="settings-form">
              <div className="form-field">
                <Label htmlFor="article-editors">Article editors</Label>
                <Select
                  value={draft.articleEditors}
                  onValueChange={(value) =>
                    setDraft((d) => ({ ...d, articleEditors: value }))
                  }
                >
                  <SelectTrigger id="article-editors" className="h-10 w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ADMIN">Admins</SelectItem>
                    <SelectItem value="LEAD">Leads and admins</SelectItem>
                    <SelectItem value="MEMBER">All users</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="flex justify-end mt-4">
              <Button
                type="submit"
                size="icon"
                disabled={busy}
                title="Save article settings"
                aria-label="Save article settings"
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
