"use client";

import "ketcher-react/dist/index.css";
import { Editor } from "ketcher-react";
import { StandaloneStructServiceProvider } from "ketcher-standalone";
import { forwardRef, useImperativeHandle, useRef } from "react";
import type { Ketcher } from "ketcher-core";

const structServiceProvider = new StandaloneStructServiceProvider();

export type SketcherHandle = {
  getSmiles: () => Promise<string>;
  clear: () => void;
};

const MoleculeSketcher = forwardRef<SketcherHandle, { onError?: (message: string) => void }>(
  function MoleculeSketcher({ onError }, ref) {
    const ketcherRef = useRef<Ketcher | null>(null);

    useImperativeHandle(ref, () => ({
      getSmiles: async () => {
        if (!ketcherRef.current) throw new Error("The sketcher has not finished loading yet.");
        // Aromatize before export: the registry's canonical SMILES use aromatic
        // (lowercase) notation, and the sketcher exports Kekulé form by default.
        // This does not fix cross-toolkit canonical atom ordering in general, but
        // it does align the common case of simple aromatic rings.
        try {
          await ketcherRef.current.aromatize();
        } catch {
          // Non-fatal: fall back to whatever bond notation was drawn.
        }
        const smiles = await ketcherRef.current.getSmiles();
        if (!smiles.trim()) throw new Error("Draw a structure before searching.");
        return smiles;
      },
      clear: () => {
        ketcherRef.current?.editor.clear();
      },
    }));

    return (
      <div className="h-[520px] w-full overflow-hidden rounded-xl border border-border" data-testid="molecule-sketcher">
        <Editor
          staticResourcesUrl=""
          structServiceProvider={structServiceProvider}
          disableMacromoleculesEditor
          errorHandler={(message) => onError?.(String(message))}
          onInit={(ketcher) => {
            ketcherRef.current = ketcher;
          }}
        />
      </div>
    );
  },
);

export default MoleculeSketcher;
