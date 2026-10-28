import { test, expect } from './fixtures/ui';
import { molecules, moleculePredictions, prediction, routes, stubBackend } from './fixtures/backend';

test('search, reuse results, run only missing models, reload, and start a new molecule', async ({ page }) => {
  await stubBackend(page);
  const saved = [...moleculePredictions];
  const submissions: string[] = [];
  await page.route(routes.moleculeSearch, (route) => {
    const query = new URL(route.request().url()).searchParams.get('query');
    return route.fulfill({ json: molecules.filter((molecule) => molecule.name === query) });
  });
  await page.route(routes.moleculeDetail, (route) => {
    const id = Number(route.request().url().split('/').pop());
    return route.fulfill({ json: molecules.find((molecule) => molecule.moleculeId === id) });
  });
  await page.route(routes.moleculePredictions, (route) => route.fulfill({ json: route.request().url().includes('/4711/') ? saved : [] }));
  await page.route(routes.predict, async (route) => {
    submissions.push(route.request().url());
    saved.push({ ...moleculePredictions[0], modelDefinitionId: 98, modelBuildId: 611, modelName: 'Model 98', algorithm: 'KNN' });
    await route.fulfill({ json: { ...prediction, modelDefinitionId: 98, modelBuildId: 611 } });
  });

  await page.goto('/search');
  const input = page.getByRole('textbox', { name: 'Molecule search' });
  await input.fill('Caffeine');
  await input.press('Enter');
  const molecule = page.locator('article').filter({ hasText: 'Caffeine' });
  await molecule.getByRole('checkbox').focus();
  await page.keyboard.press('Space');
  await page.getByRole('button', { name: 'Predict selected', exact: true }).click();
  await expect(page.getByText('Saved prediction · build #512')).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  expect(submissions).toEqual([]);
  await page.locator('tbody tr').getByRole('checkbox').check();
  await page.getByRole('button', { name: 'Run approved model' }).click();
  await expect(page.getByRole('group', { name: /Prediction for/ })).toHaveCount(2);
  await expect(page.getByText(/All listed models already have predictions/)).toBeVisible();
  expect(submissions).toHaveLength(1);
  expect(submissions[0]).toContain('/models/98/molecules/4711/predict');

  await page.reload();
  await expect(page.getByRole('group', { name: /Prediction for/ })).toHaveCount(2);
  await expect(page.locator('tbody tr')).toHaveCount(0);
  expect(submissions).toHaveLength(1);
  for (const card of await page.getByRole('group', { name: /Prediction for/ }).all()) {
    const box = await card.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width);
  }
  await page.getByRole('button', { name: 'Search another molecule' }).click();
  await expect(input).toHaveValue('');
  await expect(page).toHaveURL(/\/search$/);
  await input.fill('Aspirin');
  await input.press('Enter');
  await expect(page.locator('article').filter({ hasText: 'Aspirin' })).toBeVisible();
  await expect(page.locator('article').filter({ hasText: 'Caffeine' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Clear search', exact: true }).click();
  await expect(input).toHaveValue('');
  await expect(input).toBeFocused();
  await expect(page.getByRole('heading', { name: 'Registry matches' })).toHaveCount(0);
});
