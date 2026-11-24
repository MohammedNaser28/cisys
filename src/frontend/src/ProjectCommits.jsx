/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import { ChevronDown, RotateCw } from "lucide-react";
import { Button } from "./components/ui/button";
import { Card, CardContent } from "./components/ui/card";
import { Spinner } from "./components/ui/spinner";
import BuildStatus from "./BuildStatus";
import { buildPath, shortRevision } from "./build-format";
import { formatDateTime } from "./i18n";

const PAGE_SIZE = 50;

function retryAfterSeconds(value) {
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds > 0
    ? Math.min(Math.ceil(seconds), 3600)
    : 0;
}

function busyMessage(seconds) {
  if (seconds <= 0)
    return "Commit history is temporarily unavailable. Try again shortly.";
  return `Commit history is temporarily unavailable. Try again in ${seconds} ${
    seconds === 1 ? "second" : "seconds"
  }.`;
}

function appendUnique(current, incoming) {
  const next = [...current];
  const seen = new Set(current.map((commit) => commit.revision));
  for (const commit of incoming) {
    if (!commit.revision || seen.has(commit.revision)) continue;
    seen.add(commit.revision);
    next.push(commit);
  }
  return next;
}

/** Git history of one project branch, with links to every build of each commit. */
export default function ProjectCommits({ name, branch, load, onOpenBuild }) {
  const [commits, setCommits] = useState([]);
  const [hasMore, setHasMore] = useState(false);
  const [reason, setReason] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState(null);
  const [retryAfter, setRetryAfter] = useState(0);
  const [reload, setReload] = useState(0);
  const offsetRef = useRef(0);
  const requestRef = useRef(0);

  const fetchPage = useCallback(
    async (skip, append) => {
      if (!branch) {
        requestRef.current += 1;
        setCommits([]);
        setHasMore(false);
        setReason(null);
        setError(null);
        setRetryAfter(0);
        setLoading(false);
        setLoadingMore(false);
        return;
      }
      const request = (requestRef.current += 1);
      if (append) {
        setLoadingMore(true);
      } else {
        setCommits([]);
        setHasMore(false);
        setReason(null);
        setError(null);
        setRetryAfter(0);
        setLoading(true);
        offsetRef.current = 0;
      }
      try {
        const data = await load(
          `/project-pages/${encodeURIComponent(name)}/commits?branch=${encodeURIComponent(
            branch,
          )}&skip=${skip}&limit=${PAGE_SIZE}`,
        );
        if (request !== requestRef.current) return;
        const incoming = Array.isArray(data?.commits) ? data.commits : [];
        setCommits((current) =>
          append ? appendUnique(current, incoming) : appendUnique([], incoming),
        );
        offsetRef.current = skip + incoming.length;
        setHasMore(Boolean(data?.hasMore));
        setReason(data?.reason ?? null);
        setError(null);
        setRetryAfter(0);
      } catch (e) {
        if (request !== requestRef.current) return;
        const seconds =
          e?.status === 503 ? retryAfterSeconds(e?.retryAfter) : 0;
        setRetryAfter(seconds);
        setError({
          status: e?.status ?? null,
          message:
            e?.status === 503
              ? busyMessage(seconds)
              : e?.message || "Unable to load commits.",
        });
      } finally {
        if (request === requestRef.current) {
          setLoading(false);
          setLoadingMore(false);
        }
      }
    },
    [name, branch, load],
  );

  useEffect(() => {
    if (retryAfter <= 0) return undefined;
    const timer = setTimeout(
      () => setRetryAfter((seconds) => Math.max(0, seconds - 1)),
      1000,
    );
    return () => clearTimeout(timer);
  }, [retryAfter]);

  useEffect(() => {
    void fetchPage(0, false);
    return () => {
      requestRef.current += 1;
    };
  }, [fetchPage, reload]);

  const loadOlder = () => {
    if (loading || loadingMore || !hasMore) return;
    void fetchPage(offsetRef.current, true);
  };

  const historyNote =
    reason === "HISTORY_UNAVAILABLE"
      ? "History is unavailable for this branch. Only commits that have builds are shown."
      : reason === "HISTORY_LIMIT"
        ? "The maximum history depth was reached."
        : null;

  return (
    <Card className="project-card builds-panel">
      <CardContent>
        {historyNote && <p className="commit-note">{historyNote}</p>}
        {error && (
          <div className="commit-error">
            <div className="error-message" role="alert">
              {error.status === 503 ? busyMessage(retryAfter) : error.message}
            </div>
            <Button
              type="button"
              variant="outline"
              size="icon"
              title="Try again"
              aria-label="Try again"
              disabled={error.status === 503 && retryAfter > 0}
              onClick={() => setReload((n) => n + 1)}
            >
              <RotateCw size={16} aria-hidden="true" />
            </Button>
          </div>
        )}
        {!branch ? (
          <p>Select a branch to see its commits.</p>
        ) : loading ? (
          <p className="loading-state" role="status">
            Loading commits…
          </p>
        ) : commits.length === 0 && !error ? (
          <p>No commits</p>
        ) : commits.length > 0 ? (
          <div className="builds-table-wrap">
            <table className="builds-table commits-table">
              <thead>
                <tr>
                  <th scope="col">Commit</th>
                  <th scope="col">Message</th>
                  <th scope="col">Author</th>
                  <th scope="col">Date</th>
                  <th scope="col">Builds</th>
                </tr>
              </thead>
              <tbody>
                {commits.map((commit) => (
                  <tr key={commit.revision}>
                    <td>
                      <code
                        className="build-commit"
                        title={commit.revision ?? undefined}
                      >
                        {shortRevision(commit.revision)}
                      </code>
                    </td>
                    <td className="commit-message">{commit.message || "—"}</td>
                    <td className="build-meta commit-author">
                      {commit.author || "—"}
                    </td>
                    <td
                      className="build-meta"
                      title={commit.authoredAt ?? undefined}
                    >
                      {commit.authoredAt
                        ? formatDateTime(commit.authoredAt)
                        : "—"}
                    </td>
                    <td className="commit-builds">
                      {commit.builds?.length ? (
                        commit.builds.map((build) => (
                          <span key={build.id} className="commit-build">
                            <BuildStatus status={build.status} />
                            <a
                              className="build-link"
                              href={buildPath(
                                name,
                                build.branch || branch,
                                build.id,
                              )}
                              onClick={(event) => {
                                if (!onOpenBuild) return;
                                event.preventDefault();
                                onOpenBuild(
                                  name,
                                  build.branch || branch,
                                  build.id,
                                );
                              }}
                            >
                              #{build.number}
                            </a>
                          </span>
                        ))
                      ) : (
                        <span className="build-meta">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        {hasMore && reason !== "HISTORY_LIMIT" && !loading && !error && (
          <div className="commit-more">
            <Button
              type="button"
              variant="outline"
              size="icon"
              title={loadingMore ? "Loading…" : "Load older commits"}
              aria-label={loadingMore ? "Loading…" : "Load older commits"}
              disabled={loadingMore}
              onClick={loadOlder}
            >
              {loadingMore ? (
                <Spinner />
              ) : (
                <ChevronDown size={16} aria-hidden="true" />
              )}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
