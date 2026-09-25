"use client";

import { useEffect, useRef, useState } from "react";

type DrawerApi = {
  SvgDrawer: new (options: {width: number; height: number; padding: number}) => {draw: (tree: unknown, svg: SVGSVGElement, theme: string, weights: boolean) => void};
  parse: (smiles: string, success: (tree: unknown) => void, failure: () => void) => void;
};
let renderer: Promise<DrawerApi> | undefined;
function loadRenderer() {
  if (!renderer) renderer = new Promise<DrawerApi>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "/vendor/smiles-drawer-2.3.0.min.js";
    script.onload = () => resolve((window as unknown as {SmilesDrawer: DrawerApi}).SmilesDrawer);
    script.onerror = () => { renderer = undefined; script.remove(); reject(new Error("Renderer unavailable")); };
    document.head.appendChild(script);
  });
  return renderer;
}
function SmilesDrawing({smiles, label}: {smiles: string; label: string}) {
  const ref = useRef<SVGSVGElement>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void loadRenderer().then(api => {
      if (cancelled || !ref.current) return;
      api.parse(smiles, tree => {
        if (!cancelled && ref.current) {
          ref.current.replaceChildren();
          new api.SvgDrawer({width: 360, height: 260, padding: 18}).draw(tree, ref.current, "light", false);
        }
      }, () => { if (!cancelled) setFailed(true); });
    }).catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [smiles]);
  return failed ? <p>Drawing unavailable; SMILES is shown below.</p> : <svg ref={ref} role="img" aria-label={label} viewBox="0 0 360 260" className="my-3 h-56 w-full rounded-lg bg-white" />;
}

export function LinkedStructurePreview({ smiles, scaffold, onSearch }: {
  smiles: string; scaffold: string; onSearch: (smiles: string, scaffold: boolean) => void;
}) {
  return <section aria-label="Structures from ChemGrid" className="my-6 rounded-2xl border border-border bg-card/60 p-5">
    <h2 className="text-xl font-semibold">Structures from ChemGrid</h2>
    <p className="mt-2 text-sm text-muted-foreground">The nearest scaffold comes from ChemGrid’s prediction training set. Search the MolClass registry below for matching molecules or scaffold-containing compounds.</p>
    <div className="mt-4 grid gap-6 md:grid-cols-2">
      {[["Predicted molecule", smiles], ["Nearest training scaffold", scaffold]].map(([label, value]) => <figure key={label} className="min-w-0">
        <figcaption className="font-semibold">{label}</figcaption>
        {value ? <>
          <SmilesDrawing key={value} smiles={value} label={label} />
          <code className="block break-all text-xs">{value}</code>
          <button type="button" onClick={() => onSearch(value, label === "Nearest training scaffold")} className="mt-3 rounded-lg bg-blue-600 px-4 py-2 text-white">{label === "Nearest training scaffold" ? "Find compounds with this scaffold" : "Find this molecule"}</button>
        </> : <p className="mt-3">No ring scaffold was supplied.</p>}
      </figure>)}
    </div>
  </section>;
}
