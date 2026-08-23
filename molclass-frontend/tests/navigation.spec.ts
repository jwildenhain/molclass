import { test, expect } from "@playwright/test";
import { stubBackend } from "./fixtures/backend";

/**
 * Every route reachable from the primary navigation, asserted against the
 * heading each page actually renders. Backends are stubbed so a route failure
 * is a routing/render failure rather than a missing database.
 */

const ROUTES = [
  { link: "Upload", path: "/upload", heading: "Inspect the chemistry before it enters the model pipeline." },
  { link: "Model Creation", path: "/model-creation", heading: "Choose a dataset with a verified target." },
  { link: "Search", path: "/search", heading: "Search" },
  { link: "Model Review", path: "/model-review", heading: "Model build review" },
  { link: "Help", path: "/help", heading: "Help" },
  { link: "News", path: "/news", heading: "News" },
  { link: "Details", path: "/details", heading: "About MolClass" },
] as const;

test.beforeEach(async ({ page }) => {
  await stubBackend(page);
});

test("homepage renders the hero and the application title", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveTitle(/MolClass/);
  await expect(page.getByRole("heading", { level: 1, name: "MolClass V2" })).toBeVisible();
  await expect(page.getByText("bioactivity prediction")).toBeVisible();
});

for (const route of ROUTES) {
  test(`desktop navigation reaches ${route.path}`, async ({ page }) => {
    await page.goto("/");
    await page.getByRole("navigation").getByRole("link", { name: route.link, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${route.path}$`));
    await expect(page.getByRole("heading", { level: 1, name: route.heading })).toBeVisible();
  });
}

test("every navigation target is a real route, not a 404", async ({ page }) => {
  for (const route of ROUTES) {
    const response = await page.goto(route.path);
    expect(response?.status(), `${route.path} should resolve`).toBe(200);
  }
});

test("mobile viewport exposes navigation through the Menu disclosure", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("/");

  const nav = page.getByRole("navigation");
  await expect(nav.getByRole("link", { name: "Upload", exact: true })).toBeHidden();

  const menu = page.getByText("Menu", { exact: true });
  await expect(menu).toBeVisible();
  await menu.click();

  await expect(nav.getByRole("link", { name: "Search", exact: true })).toBeVisible();
  await expect(nav.getByRole("link", { name: "Model Review", exact: true })).toBeVisible();
});

test("theme toggle switches the document colour scheme", async ({ page }) => {
  await page.goto("/");
  const html = page.locator("html");
  const before = await html.getAttribute("class");

  await page.getByRole("button", { name: /theme/i }).click();
  await expect
    .poll(async () => html.getAttribute("class"))
    .not.toBe(before);
});
