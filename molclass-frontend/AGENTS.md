<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

## UI regression checks

For changes to molecule search, registration, model selection, or prediction results,
run `npm run test:ui` before declaring the change ready or deploying the frontend.
This builds the standalone production server and checks desktop/mobile workflows.
Use the source revision intended for deployment; do not substitute a previously
running dev server. `../tools/build-frontend.sh` runs the same gate.

Keep regressions in the critical suite and use `tests/fixtures/ui.ts` so browser
exceptions and unmocked API requests fail the test. Test failure/slow-response
states with local fixtures, never by creating production jobs. Preserve failure
artifacts and investigate failures instead of adding retries or weakening checks.
See README.md for coverage, browser installation, and report commands.
