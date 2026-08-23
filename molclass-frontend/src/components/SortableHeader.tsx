"use client";

import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react";

export type SortDirection = "asc" | "desc";

/** A <th> whose whole label acts as a sort toggle: unsorted -> asc -> desc -> asc ... */
export function SortableHeader({
  label,
  active,
  direction,
  onClick,
  align = "left",
  className = "",
}: {
  label: string;
  active: boolean;
  direction: SortDirection;
  onClick: () => void;
  align?: "left" | "right";
  className?: string;
}) {
  const Icon = active ? (direction === "asc" ? ArrowUp : ArrowDown) : ArrowUpDown;
  return (
    <th className={className} aria-sort={active ? (direction === "asc" ? "ascending" : "descending") : "none"}>
      <button
        type="button"
        onClick={onClick}
        className={`inline-flex items-center gap-1 font-semibold transition-colors hover:text-foreground ${
          active ? "text-foreground" : "text-muted-foreground"
        } ${align === "right" ? "flex-row-reverse" : ""}`}
      >
        {label}
        <Icon className={`h-3.5 w-3.5 shrink-0 ${active ? "opacity-100" : "opacity-40"}`} />
      </button>
    </th>
  );
}

/** Toggle helper for the common "click same column flips direction, click new column resets to asc" pattern. */
export function nextSort<K>(
  key: K,
  current: { key: K; direction: SortDirection } | null,
): { key: K; direction: SortDirection } {
  if (current?.key === key) {
    return { key, direction: current.direction === "asc" ? "desc" : "asc" };
  }
  return { key, direction: "asc" };
}
