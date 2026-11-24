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
import BuildComments from "./BuildComments";

afterEach(cleanup);

const comments = [
  {
    id: 2,
    author: "maria",
    body: "Mine",
    createdAt: "2026-11-19T10:00:00Z",
    updatedAt: "2026-11-19T11:00:00Z",
    mine: true,
    canDelete: true,
  },
  {
    id: 1,
    author: "omar",
    body: "Theirs",
    createdAt: "2026-11-19T09:00:00Z",
    updatedAt: null,
    mine: false,
    canDelete: false,
  },
];

function renderComments({ canComment = true, load } = {}) {
  const api = load ?? vi.fn(async () => comments);
  render(
    <BuildComments
      name="demo"
      projectId={7}
      buildId={42}
      canComment={canComment}
      load={api}
    />,
  );
  return api;
}

describe("BuildComments", () => {
  it("lists comments and marks edited ones", async () => {
    const api = renderComments();
    expect(await screen.findByText("Mine")).toBeTruthy();
    expect(screen.getByText("Theirs")).toBeTruthy();
    expect(screen.getAllByText("(edited)")).toHaveLength(1);
    expect(api).toHaveBeenCalledWith("/project-pages/demo/builds/42/comments");
  });

  it("offers edit on own comments only", async () => {
    renderComments();
    await screen.findByText("Mine");
    expect(screen.getAllByLabelText("Edit comment")).toHaveLength(1);
  });

  it("hides the form and edit when commenting is not allowed", async () => {
    renderComments({ canComment: false });
    await screen.findByText("Mine");
    expect(screen.queryByLabelText("New comment")).toBeNull();
    expect(screen.queryByLabelText("Edit comment")).toBeNull();
  });

  it("offers delete where allowed and deletes after confirming", async () => {
    const api = vi.fn(async () => comments);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderComments({ load: api });
    await screen.findByText("Mine");
    expect(screen.getAllByLabelText("Delete comment")).toHaveLength(1);
    fireEvent.click(screen.getByLabelText("Delete comment"));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/projects/7/builds/42/comments/2", {
        method: "DELETE",
      }),
    );
    window.confirm.mockRestore();
  });

  it("posts a new comment", async () => {
    const api = vi.fn(async () => comments);
    renderComments({ load: api });
    await screen.findByText("Mine");
    fireEvent.change(screen.getByLabelText("New comment"), {
      target: { value: "Hello" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add comment" }));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/projects/7/builds/42/comments", {
        method: "POST",
        body: JSON.stringify({ body: "Hello" }),
      }),
    );
  });

  it("saves an edit", async () => {
    const api = vi.fn(async () => comments);
    renderComments({ load: api });
    await screen.findByText("Mine");
    fireEvent.click(screen.getByLabelText("Edit comment"));
    fireEvent.change(screen.getByLabelText("Comment"), {
      target: { value: "Changed" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/projects/7/builds/42/comments/2", {
        method: "PUT",
        body: JSON.stringify({ body: "Changed" }),
      }),
    );
  });
});
