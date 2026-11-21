/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useMemo, useState } from "react";
import {
  Check,
  ChevronDown,
  CheckCheck,
  Eraser,
  Search,
  X,
} from "lucide-react";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Checkbox } from "../ui/checkbox";
import { Input } from "../ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";

const MAX_CHIPS = 3;

/**
 * A searchable multi-select for projects. "Select all" applies to the projects
 * currently visible after the search, so a filter cannot select hidden ones by
 * accident. The selected projects are kept in the order they were added.
 */
export default function ProjectPicker({
  projects,
  value,
  onChange,
  idPrefix,
  label = "Projects",
  disabled = false,
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");

  const selected = useMemo(() => new Set(value), [value]);
  const selectedProjects = projects.filter((p) => selected.has(p.id));
  const visible = projects.filter((p) =>
    p.name.toLowerCase().includes(query.trim().toLowerCase()),
  );
  const allVisibleSelected =
    visible.length > 0 && visible.every((p) => selected.has(p.id));

  function toggle(id) {
    onChange(
      selected.has(id)
        ? value.filter((projectId) => projectId !== id)
        : [...value, id],
    );
  }

  function selectAllVisible() {
    const ids = visible.map((p) => p.id);
    onChange([...new Set([...value, ...ids])]);
  }

  function changeOpen(next) {
    setOpen(next);
    if (!next) setQuery("");
  }

  const summary =
    selectedProjects.length === 0
      ? projects.length === 0
        ? "No projects available"
        : "Select projects"
      : selectedProjects.length === projects.length
        ? `All ${projects.length} projects`
        : `${selectedProjects.length} ${
            selectedProjects.length === 1 ? "project" : "projects"
          } selected`;

  return (
    <div className="project-picker">
      <Popover open={open} onOpenChange={changeOpen}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            variant="outline"
            className="project-picker-trigger"
            disabled={disabled || projects.length === 0}
            aria-label={label}
            aria-expanded={open}
          >
            <span className="project-picker-summary">{summary}</span>
            <ChevronDown size={16} aria-hidden="true" />
          </Button>
        </PopoverTrigger>
        <PopoverContent align="start" className="project-picker-popover">
          <div className="project-picker-search">
            <Search size={16} aria-hidden="true" />
            <Input
              type="search"
              aria-label="Search projects"
              placeholder="Search projects…"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </div>
          <div
            className="project-picker-list"
            role="group"
            aria-label={`${label} list`}
          >
            {visible.length === 0 ? (
              <p className="project-picker-empty">No projects match.</p>
            ) : (
              visible.map((project) => (
                <div
                  key={project.id}
                  className="project-picker-option"
                  data-selected={selected.has(project.id)}
                  onClick={(event) => {
                    if (event.target.closest('[data-slot="checkbox"]')) return;
                    toggle(project.id);
                  }}
                >
                  <Checkbox
                    id={`${idPrefix}-${project.id}`}
                    aria-label={project.name}
                    checked={selected.has(project.id)}
                    onCheckedChange={() => toggle(project.id)}
                  />
                  <span>{project.name}</span>
                </div>
              ))
            )}
          </div>
          <div className="project-picker-footer">
            <span className="project-picker-count">
              {selectedProjects.length} of {projects.length} selected
            </span>
            <div className="project-picker-actions">
              <Button
                type="button"
                variant="ghost"
                size="icon"
                title="Select all"
                aria-label="Select all"
                disabled={visible.length === 0 || allVisibleSelected}
                onClick={selectAllVisible}
              >
                <CheckCheck size={16} aria-hidden="true" />
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                title="Clear"
                aria-label="Clear"
                disabled={value.length === 0}
                onClick={() => onChange([])}
              >
                <Eraser size={16} aria-hidden="true" />
              </Button>
              <Button
                type="button"
                size="icon"
                title="Done"
                aria-label="Done"
                onClick={() => changeOpen(false)}
              >
                <Check size={16} aria-hidden="true" />
              </Button>
            </div>
          </div>
        </PopoverContent>
      </Popover>
      {selectedProjects.length > 0 && (
        <div className="project-picker-chips">
          {selectedProjects.slice(0, MAX_CHIPS).map((project) => (
            <Badge
              key={project.id}
              variant="secondary"
              className="project-chip"
            >
              {project.name}
              <button
                type="button"
                className="project-chip-remove"
                aria-label={`Remove ${project.name}`}
                onClick={() => toggle(project.id)}
              >
                <X size={12} aria-hidden="true" />
              </button>
            </Badge>
          ))}
          {selectedProjects.length > MAX_CHIPS && (
            <Badge variant="outline">
              +{selectedProjects.length - MAX_CHIPS} more
            </Badge>
          )}
        </div>
      )}
    </div>
  );
}
