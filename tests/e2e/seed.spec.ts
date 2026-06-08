// seed.spec.ts — Quality lever: the exemplar every generated E2E test is modeled on.
// Adapted to FinanceHQ routes and roles from the real accessibility tree.
// Demonstrates: role-based locators, test independence, wait-for-state, risk-tied name.
// risk: test-plan.md #5 — SPA deep-link forwarding works in prod build

import { test, expect } from '@playwright/test';

test('unauthenticated user visiting a guarded route is redirected to login', async ({ page }) => {
  // Navigate directly to a guarded route (deep link with no prior auth)
  await page.goto('/dashboard');

  // SpaForwardingConfig serves index.html for /dashboard;
  // Angular router fires; auth guard redirects to /login?returnUrl=%2Fdashboard.
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
});
