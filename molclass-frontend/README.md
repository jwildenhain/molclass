# MolClass frontend

Next.js frontend for registry search, molecule registration, saved predictions,
model selection, and model review. Production runs the standalone build in Docker
at `https://molclass.chemgrid.org`.

## Development and builds

```sh
npm ci
npm run dev
```

The development server defaults to port 3000. `npm run typecheck` checks application
types; `npm run typecheck:tests` checks browser-test code. API rewrites use
`API_URL` and `PREDICTOR_API_URL` at build time; production Docker Compose supplies
the container addresses. Preserve those settings when rebuilding.

`npm run build` creates `.next/standalone/server.js`; its postbuild step stages the
public assets and client chunks. Start that deployment with
`HOSTNAME=127.0.0.1 PORT=3000 node .next/standalone/server.js`.
Use `../tools/build-frontend.sh` for a build with the UI regression gate.

For current behavior, API contracts, and the 2026-10-28 frontend deployment record,
see [search UI](../docs/V3_SEARCH_UI.md) and
[prediction API](../docs/V3_PREDICTION_API.md).

## UI regression gate

Before deploying frontend changes, run:

```sh
npm ci
npx playwright install --with-deps
npm run test:ui
```

This builds and tests the production app on an isolated local port (3101), using
Chromium, Firefox, WebKit, Android Chrome, and iPhone Safari emulation. All API
responses are fixtures; an unmocked API call or browser exception fails the test.
No production predictions or registrations are submitted. An existing server is
never reused, and failures are not retried away.

The gate covers:

- Registry search, selection, model handoff, deep links, and browser Back.
- Clearing results, selection, and linked structures; typing another query;
  delayed responses after clearing; starting over from predictions.
- Registration followed by model selection.
- Saved predictions on arrival and reload, missing-only submissions, model build
  changes, history errors/retry, and multiple molecules.
- Incremental model results, pending/error cards, progress, desktop grids, mobile
  stacking and overflow, and keyboard search/selection.

`tools/build-frontend.sh` runs this gate before reporting a deployable build.
GitHub Actions also runs it for pull requests and pushes to `main` or `master`.
Make **Production UI regression gate** a required branch-protection check before
merging; repository files alone cannot enforce that server-side setting.
Direct Docker builds bypass the wrapper, so run the gate before deploying those.

On failure, inspect `playwright-report/ui/index.html` (or run
`npx playwright show-report playwright-report/ui`). Screenshots, traces, and videos
are retained in `test-results/ui/` and uploaded by CI. To diagnose one scenario:

```sh
npm run test:ui -- --project=desktop-chromium --grep "search, reuse results"
```

These checks protect the listed interactions; they do not replace a read-only
post-deployment check of production API connectivity and real saved results.
