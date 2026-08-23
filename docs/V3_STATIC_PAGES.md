# V3 static/informational pages

Covers `/news`, `/help`, and `/details` (About), and the top-bar nav changes that came with them.
These are content pages with no backend of their own — everything below is frontend-only.

## Navigation

Top bar order is now: Upload, Model Creation, Search, Model Review, **Help**, **News**, Details.

`Prediction List` was removed from the nav (both desktop and, previously, absent from mobile). The
route itself — `/prediction-list`, its redirects, and its Playwright coverage — is untouched and
still fully reachable by URL; it's just no longer linked from the top bar.

## `/news`

Write-up of what MolClass does plus a benchmark table comparing MolClass (default RandomForest on
the JUMBO feature profile, untuned) against DeepTox, the winning method of the 2014 NIH Tox21 Data
Challenge. All per-endpoint AUC values and the methodology caveats are copied from the
already-verified comparison in
[V3_DESCRIPTOR_CATALOG_INCIDENT_2026-08-23.md §5.7](V3_DESCRIPTOR_CATALOG_INCIDENT_2026-08-23.md) —
if that source table is ever corrected, `src/app/news/page.tsx`'s `ROWS`/`PANEL_ROWS`/`OVERALL`
constants need updating to match.

## `/help`

A five-step illustrated walkthrough of the core workflow: upload → configure a model → review/
approve the build → search & predict → read a molecule's result and history.

Each step's `PagePreview` is a hand-built illustration of that page's real layout, not a captured
screenshot — this session's Browser-pane tooling couldn't composite frames to take one
(`document.hidden: true` regardless of navigation; the same environment characteristic diagnosed
earlier for automated interaction, see the incident doc). The page says so explicitly rather than
passing the illustrations off as real captures. Swapping in actual screenshots later just means
re-running with the preview pane genuinely visible and replacing each `PagePreview`'s children with
an `<img>`.

## `/details` (About)

- "Version History (V2)" has a "Recent improvements" list — kept manually in sync with notable
  engineering work; not generated from git history.
- Contact block links out to LinkedIn rather than a mailto link.
- Header uses the same left-aligned kicker/h1/subtitle pattern as every other page (fixed from an
  earlier centered layout that had drifted from the rest of the site).
