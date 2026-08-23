"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { FlaskConical, Network } from "lucide-react";
import { StructureSearchPanel } from "./StructureSearchPanel";
import { ModelMoleculeSearchPanel } from "./ModelMoleculeSearchPanel";

const TABS = [
  {
    id: "structure",
    label: "Structure search",
    hint: "Find a molecule in the registry",
    icon: FlaskConical,
  },
  {
    id: "models",
    label: "Model & molecule search",
    hint: "Predict with an approved model",
    icon: Network,
  },
] as const;

type TabId = (typeof TABS)[number]["id"];

function isTabId(value: string | null): value is TabId {
  return TABS.some((tab) => tab.id === value);
}

export function SearchTabs() {
  const params = useSearchParams();
  const requested = params.get("tab");
  const active: TabId = isTabId(requested) ? requested : "structure";

  return (
    <>
      <div role="tablist" aria-label="Search mode" className="flex flex-wrap gap-2 border-b border-border pb-px">
        {TABS.map((tab) => {
          const selected = tab.id === active;
          const Icon = tab.icon;
          return (
            <Link
              key={tab.id}
              href={tab.id === "structure" ? "/search" : `/search?tab=${tab.id}`}
              role="tab"
              aria-selected={selected}
              scroll={false}
              className={`-mb-px inline-flex items-center gap-2 rounded-t-xl border-b-2 px-4 py-3 text-sm font-semibold transition sm:px-5 ${
                selected
                  ? "border-blue-500 text-foreground"
                  : "border-transparent text-muted-foreground hover:border-border hover:text-foreground"
              }`}
            >
              <Icon className="h-4 w-4" />
              <span>{tab.label}</span>
              <span className="hidden text-xs font-normal text-muted-foreground lg:inline">— {tab.hint}</span>
            </Link>
          );
        })}
      </div>

      {active === "structure" ? <StructureSearchPanel /> : <ModelMoleculeSearchPanel />}
    </>
  );
}
