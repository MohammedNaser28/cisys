/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ArticlesPage from "./ArticlesPage";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const ARTICLE = {
  id: 7,
  title: "FAQ",
  body: "## Why\n\nBecause <script>alert(1)</script>",
  author: "alice",
  createdAt: "2026-11-20T10:00:00Z",
  updatedAt: "2026-11-20T10:00:00Z",
  canEdit: true,
};

describe("ArticlesPage", () => {
  it("lists the articles without edit controls for readers", async () => {
    const load = vi.fn(async () => ({
      canEdit: false,
      articles: [
        { id: 7, title: "FAQ", author: "alice", updatedAt: ARTICLE.updatedAt },
      ],
    }));
    const onOpen = vi.fn();
    render(
      <ArticlesPage
        route={{ id: null, edit: false }}
        load={load}
        onOpen={onOpen}
      />,
    );

    fireEvent.click(await screen.findByText("FAQ"));
    expect(load).toHaveBeenCalledWith("/articles");
    expect(onOpen).toHaveBeenCalledWith({ id: 7 });
    expect(screen.queryByLabelText("New article")).toBeNull();
  });

  it("offers a new article to editors", async () => {
    const load = vi.fn(async () => ({ canEdit: true, articles: [] }));
    const onOpen = vi.fn();
    render(
      <ArticlesPage
        route={{ id: null, edit: false }}
        load={load}
        onOpen={onOpen}
      />,
    );

    fireEvent.click(await screen.findByLabelText("New article"));
    expect(onOpen).toHaveBeenCalledWith({ edit: true });
  });

  it("renders the markdown without raw HTML", async () => {
    const load = vi.fn(async () => ({ ...ARTICLE, canEdit: false }));
    const { container } = render(
      <ArticlesPage
        route={{ id: 7, edit: false }}
        load={load}
        onOpen={vi.fn()}
      />,
    );

    expect(await screen.findByRole("heading", { name: "Why" })).toBeTruthy();
    expect(container.querySelector("script")).toBeNull();
    expect(screen.queryByLabelText("Edit article")).toBeNull();
    expect(screen.queryByLabelText("Delete article")).toBeNull();
  });

  it("deletes an article after confirming", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const load = vi.fn(async (path, opts) =>
      opts?.method === "DELETE" ? null : ARTICLE,
    );
    const onOpen = vi.fn();
    render(
      <ArticlesPage
        route={{ id: 7, edit: false }}
        load={load}
        onOpen={onOpen}
      />,
    );

    fireEvent.click(await screen.findByLabelText("Delete article"));
    await waitFor(() => expect(onOpen).toHaveBeenCalledWith({}));
    expect(load).toHaveBeenCalledWith("/articles/7", { method: "DELETE" });
  });

  it("creates an article", async () => {
    const load = vi.fn(async () => ({ ...ARTICLE, id: 9 }));
    const onOpen = vi.fn();
    render(
      <ArticlesPage
        route={{ id: null, edit: true }}
        load={load}
        onOpen={onOpen}
      />,
    );

    fireEvent.change(screen.getByLabelText("Title"), {
      target: { value: "Setup" },
    });
    fireEvent.change(screen.getByLabelText("Body"), {
      target: { value: "Steps" },
    });
    fireEvent.click(screen.getByLabelText("Save"));

    await waitFor(() => expect(onOpen).toHaveBeenCalledWith({ id: 9 }));
    expect(load).toHaveBeenCalledWith("/articles", {
      method: "POST",
      body: JSON.stringify({ title: "Setup", body: "Steps" }),
    });
  });

  it("edits an article and previews it", async () => {
    const load = vi.fn(async () => ARTICLE);
    const onOpen = vi.fn();
    render(
      <ArticlesPage
        route={{ id: 7, edit: true }}
        load={load}
        onOpen={onOpen}
      />,
    );

    await waitFor(() =>
      expect(screen.getByLabelText("Title").value).toBe("FAQ"),
    );
    fireEvent.change(screen.getByLabelText("Title"), {
      target: { value: "FAQ 2" },
    });
    fireEvent.click(screen.getByLabelText("Preview"));
    expect(screen.getByRole("heading", { name: "Why" })).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Save"));

    await waitFor(() => expect(onOpen).toHaveBeenCalledWith({ id: 7 }));
    expect(load).toHaveBeenLastCalledWith("/articles/7", {
      method: "PUT",
      body: JSON.stringify({ title: "FAQ 2", body: ARTICLE.body }),
    });
  });
});
