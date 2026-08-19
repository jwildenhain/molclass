"use client";

import { useState } from "react";
import { Beaker } from "lucide-react";

/**
 * Renders a molecule's stored structure as a 2D depiction, fetched from the Spring
 * predictor's CDK-backed /api/v3/molecules/{id}/structure.svg endpoint. Plain <img>
 * rather than next/image: the content is dynamically generated SVG proxied same-origin,
 * not a static asset next/image's optimizer is built for.
 */
export function MoleculeStructure({
  moleculeId,
  size = 96,
  className = "",
}: {
  moleculeId: number;
  size?: number;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);

  if (failed) {
    return (
      <div
        className={`grid shrink-0 place-items-center rounded-lg border border-dashed border-border bg-muted/40 text-muted-foreground ${className}`}
        style={{ width: size, height: size }}
        title="Structure could not be rendered"
      >
        <Beaker className="h-1/3 w-1/3 opacity-50" />
      </div>
    );
  }

  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={`/api/v3/molecules/${moleculeId}/structure.svg`}
      alt={`Structure of molecule ${moleculeId}`}
      width={size}
      height={size}
      loading="lazy"
      onError={() => setFailed(true)}
      className={`shrink-0 rounded-lg border border-border bg-white object-contain ${className}`}
      style={{ width: size, height: size }}
    />
  );
}
