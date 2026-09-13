# Analytics integration

MolClass loads a single shared script tag in `molclass-frontend/src/app/layout.tsx`:

```tsx
<Script src="/chemgrid-analytics.js" strategy="afterInteractive" />
```

## Where the script actually lives

`/chemgrid-analytics.js` is **not** part of this repository. It's a single shared script served by
Apache at the infrastructure level (`/var/www/html/chemgrid-analytics.js` on the production host,
configured via `/etc/apache2/conf-available/chemgrid-analytics.conf` and
`chemgrid-analytics-static.conf`), reused across every app on that box: `chemgrid.org`, MolClass,
and the BoxPlotR / CheckerboardR / GrowthCurve Shiny apps. The `<Script>` tag in `layout.tsx` is a
pointer to that shared file, not an embedded copy — any other deployment target (local dev, a
different host) simply gets a harmless 404 for that path and no analytics fire.

This is a deliberate split: the script is infrastructure shared across five otherwise-unrelated
apps, keyed by `window.location.hostname`, so it belongs with that shared infrastructure rather
than duplicated into each app's repo.

## MolClass's GA4 property

The script maps hostnames to Google Analytics 4 measurement IDs internally:

| Hostname | Measurement ID |
|---|---|
| `molclass.chemgrid.org` | `G-0S0YE5CENT` |
| `chemgrid.org` / `www.chemgrid.org` | `G-LMCPLNFJPV` |
| `boxplotr.chemgrid.org` | `G-J42YT6MQP8` |
| `checkerboardr.chemgrid.org` | `G-KFX6ZPPV76` |
| `growthcurve.chemgrid.org` | `G-2WLRQJV9NP` |

A GA4 measurement ID is a public identifier (it ships in every page's client-side source by
design, the same way a site's domain name does) — not a secret, safe to record here.

## What it tracks, for MolClass specifically

- Standard page views on every client-side navigation (SPA-aware: patches `history.pushState` /
  `replaceState` and listens for `popstate` so route changes register, not just hard loads).
- `app_action` — clicks on any `button` or `[role=button]`.
- `navigation` — clicks on same-origin links.
- `input_change` — changes on any `input`/`select`/`textarea`, identified by `id`/`name`/
  `aria-label`, never by value.
- `form_submit` — form submissions, identified by `id`/`name`.

It explicitly excludes known bots/crawlers (`Googlebot`, `bingbot`, `Baiduspider`, etc. via
user-agent sniffing) and disables `allow_google_signals` / `allow_ad_personalization_signals`.

## If this ever needs to change

Editing the shared script (e.g. adding a new tracked event, rotating a measurement ID) means SSHing
into the production host and editing `/var/www/html/chemgrid-analytics.js` directly — there's
nothing to redeploy from this repo for that. If MolClass ever needs analytics that the other four
apps shouldn't share, that's the point to fork it into something MolClass-specific and drop the
shared file for this app.
