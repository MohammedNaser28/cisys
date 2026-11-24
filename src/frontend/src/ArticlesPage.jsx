/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { ChevronLeft, Pencil, Plus, Trash2 } from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Textarea } from "./components/ui/textarea";
import PageHeader from "./components/layout/PageHeader";
import Markdown from "./Markdown";
import { articlesPath } from "./project-paths";
import { formatDate } from "./i18n";

function message(e, fallback) {
  return e.status === 404 ? "Article not found." : e.message || fallback;
}

function Link({ to, onOpen, children, className }) {
  return (
    <a
      href={articlesPath(to)}
      className={className}
      onClick={(event) => {
        event.preventDefault();
        onOpen(to);
      }}
    >
      {children}
    </a>
  );
}

function Back({ to = {}, onOpen }) {
  return (
    <Link to={to} onOpen={onOpen} className="article-back">
      <ChevronLeft size={16} aria-hidden="true" /> Articles
    </Link>
  );
}

function ErrorMessage({ error }) {
  return (
    error && (
      <div className="error-message" role="alert">
        {error}
      </div>
    )
  );
}

function ArticleList({ load, onOpen }) {
  const [listing, setListing] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    load("/articles")
      .then(setListing)
      .catch((e) => setError(message(e, "Unable to load the articles.")));
  }, [load]);

  return (
    <>
      <PageHeader
        title="Articles"
        actions={
          listing?.canEdit && (
            <Button type="button" onClick={() => onOpen({ edit: true })}>
              <Plus size={16} aria-hidden="true" /> New article
            </Button>
          )
        }
      />
      <ErrorMessage error={error} />
      {listing && (
        <ul className="article-list">
          {listing.articles.map((a) => (
            <li key={a.id}>
              <Link to={{ id: a.id }} onOpen={onOpen} className="article-link">
                {a.title}
              </Link>
              <span className="article-meta">
                {a.author} · {formatDate(a.updatedAt)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function ArticleView({ id, load, onOpen }) {
  const [article, setArticle] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    load(`/articles/${id}`)
      .then(setArticle)
      .catch((e) => setError(message(e, "Unable to load the article.")));
  }, [load, id]);

  async function remove() {
    if (!window.confirm(`Delete ${article.title}?`)) return;
    setBusy(true);
    setError("");
    try {
      await load(`/articles/${id}`, { method: "DELETE" });
      onOpen({});
    } catch (e) {
      setError(message(e, "Unable to delete the article."));
      setBusy(false);
    }
  }

  return (
    <>
      <Back onOpen={onOpen} />
      <ErrorMessage error={error} />
      {article && (
        <article className="manual article">
          <h1>{article.title}</h1>
          <div className="article-meta">
            <span>
              {article.author} · {formatDate(article.updatedAt)}
            </span>
            {article.canEdit && (
              <span className="article-actions">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  title="Edit"
                  aria-label="Edit article"
                  onClick={() => onOpen({ id, edit: true })}
                >
                  <Pencil size={14} aria-hidden="true" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  title="Delete"
                  aria-label="Delete article"
                  disabled={busy}
                  onClick={remove}
                >
                  <Trash2 size={14} aria-hidden="true" />
                </Button>
              </span>
            )}
          </div>
          <Markdown>{article.body}</Markdown>
        </article>
      )}
    </>
  );
}

function ArticleEditor({ id, load, onOpen }) {
  const [draft, setDraft] = useState(
    id === null ? { title: "", body: "" } : null,
  );
  const [preview, setPreview] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (id === null) return;
    load(`/articles/${id}`)
      .then((a) => setDraft({ title: a.title, body: a.body }))
      .catch((e) => setError(message(e, "Unable to load the article.")));
  }, [load, id]);

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const saved = await load(id === null ? "/articles" : `/articles/${id}`, {
        method: id === null ? "POST" : "PUT",
        body: JSON.stringify(draft),
      });
      onOpen({ id: saved.id });
    } catch (e) {
      setError(message(e, "Unable to save the article."));
      setBusy(false);
    }
  }

  const cancel = id === null ? {} : { id };

  return (
    <>
      <Back to={cancel} onOpen={onOpen} />
      <PageHeader title={id === null ? "New article" : "Edit article"} />
      <ErrorMessage error={error} />
      {draft && (
        <form className="article-form" onSubmit={submit}>
          <div className="form-field">
            <Label htmlFor="article-title">Title</Label>
            <Input
              id="article-title"
              value={draft.title}
              maxLength={200}
              onChange={(e) =>
                setDraft((d) => ({ ...d, title: e.target.value }))
              }
            />
          </div>
          <div className="article-tabs" role="group" aria-label="Body view">
            <Button
              type="button"
              variant={preview ? "ghost" : "outline"}
              aria-pressed={!preview}
              onClick={() => setPreview(false)}
            >
              Write
            </Button>
            <Button
              type="button"
              variant={preview ? "outline" : "ghost"}
              aria-pressed={preview}
              onClick={() => setPreview(true)}
            >
              Preview
            </Button>
          </div>
          {preview ? (
            <div className="manual article article-preview">
              <Markdown>{draft.body}</Markdown>
            </div>
          ) : (
            <Textarea
              aria-label="Body"
              className="article-body"
              value={draft.body}
              onChange={(e) =>
                setDraft((d) => ({ ...d, body: e.target.value }))
              }
            />
          )}
          <div className="comment-form-actions">
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpen(cancel)}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={busy || !draft.title.trim() || !draft.body.trim()}
            >
              Save
            </Button>
          </div>
        </form>
      )}
    </>
  );
}

/** The article list, one article, or the editor, as {@code route} ({ id, edit }) says. */
export default function ArticlesPage({ route, load, onOpen }) {
  useEffect(() => {
    window.scrollTo?.(0, 0);
  }, [route.id, route.edit]);

  return (
    <main id="articles" className="page-container">
      {route.edit ? (
        <ArticleEditor
          key={route.id ?? "new"}
          id={route.id}
          load={load}
          onOpen={onOpen}
        />
      ) : route.id !== null ? (
        <ArticleView key={route.id} id={route.id} load={load} onOpen={onOpen} />
      ) : (
        <ArticleList load={load} onOpen={onOpen} />
      )}
    </main>
  );
}
