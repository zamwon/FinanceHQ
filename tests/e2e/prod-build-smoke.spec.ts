// risk: test-plan.md #5 — Frontend production build diverges from dev
// seed: tests/e2e/seed.spec.ts
//
// Proves: Angular prod build served by Spring Boot renders login, allows login,
// shows dashboard, adds and removes obligations — same flow as ng serve.
// Specifically covers: lazy-load bundle resolution, API routing without proxy,
// SPA deep-link forwarding, and auth guard redirect.
//
// Precondition: Spring Boot prod JAR must be running on port 8080.
// Start with: java -jar target/finance-hq-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
//
// Note: login through UI is intentional here — the auth flow is part of the risk oracle.
// storageState is not used because testing that registration and login work in the
// prod build (without the ng serve proxy) is the purpose of this test.

import { test, expect } from '@playwright/test';

test('prod build serves auth flow, dashboard, and obligations without proxy', async ({ page }) => {
  const ts = Date.now();
  const email = `smoke-${ts}@example.com`;
  const password = 'Smoke1234!';
  const obligationName = `Smoke ${ts}`;

  // ── Step 1: Login page loads from prod build ──────────────────────────────
  // Proves: Spring Boot serves index.html, Angular bootstraps, CSS renders,
  // lazy-loaded LoginComponent bundle resolves.
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();

  // ── Step 2: Register a new user ───────────────────────────────────────────
  // Proves: link navigates (Angular router), RegisterComponent lazy bundle resolves,
  // POST /auth/register routed by Spring Boot directly (no dev-server proxy).
  await page.getByRole('link', { name: 'Register' }).click();
  await page.waitForURL('**/register');
  await expect(page.getByRole('heading', { name: 'Create account' })).toBeVisible();
  await page.getByRole('textbox', { name: 'you@example.com' }).fill(email);
  // Password fields have no accessible name (spartan-ng label in sibling div, not <label for>).
  // See e2e-quality-rules.md for context.
  await page.getByRole('textbox').nth(1).fill(password);
  await page.getByRole('textbox').nth(2).fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();
  await page.waitForURL('**/login');

  // ── Step 3: Login ─────────────────────────────────────────────────────────
  // Proves: POST /auth/login works, JWT stored, Angular router navigates to /dashboard.
  await page.getByRole('textbox', { name: 'you@example.com' }).fill(email);
  await page.getByRole('textbox').nth(1).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL('**/dashboard');

  // ── Step 4: Dashboard renders ─────────────────────────────────────────────
  // Proves: ObligationsComponent lazy bundle resolves, GET /api/obligations returns,
  // spartan.ui components render with Tailwind CSS from the prod styles bundle.
  await expect(page.getByRole('heading', { name: 'Obligations' })).toBeVisible();
  await expect(page.getByRole('button', { name: '+ Add Obligation' }).first()).toBeVisible();
  await expect(page.getByText('0 active obligations')).toBeVisible();

  // ── Step 5: Add an obligation ─────────────────────────────────────────────
  // Proves: POST /api/obligations routed by Spring Boot (no proxy), dialog renders.
  await page.getByRole('button', { name: '+ Add Obligation' }).first().click();
  await expect(page.getByRole('heading', { name: 'Add Obligation' })).toBeVisible();
  await page.getByRole('textbox', { name: 'e.g. Rent' }).fill(obligationName);
  // Spinbuttons have no accessible name — see e2e-quality-rules.md.
  await page.getByPlaceholder('0.00').fill('500');
  await page.getByPlaceholder('1–').fill('15');
  await page.getByRole('button', { name: 'Save' }).click();

  // ── Step 6: Obligation appears in the list ────────────────────────────────
  // Proves: response from POST triggers reload, GET /api/obligations succeeds,
  // data survives the round-trip and renders without CSS breakage.
  await expect(page.getByText(obligationName)).toBeVisible();
  await expect(page.getByText('1 active obligation')).toBeVisible();

  // ── Cleanup: delete the test obligation ──────────────────────────────────
  await page.getByRole('button', { name: '🗑' }).first().click();
  await expect(page.getByRole('heading', { name: 'Delete obligation?' })).toBeVisible();
  await page.getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByText('0 active obligations')).toBeVisible();

  // ── Step 7: Sign out ──────────────────────────────────────────────────────
  // Proves: POST /auth/logout clears tokens, Angular navigates back to /login.
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.waitForURL('**/login');
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();

  // ── Step 8: Deep link — /dashboard redirects unauthenticated user to login ─
  // Proves: SpaForwardingConfig serves index.html for /dashboard (not 404),
  // Angular bootstraps again, auth guard fires, redirects to /login?returnUrl=%2Fdashboard.
  // This is the unique prod-build failure mode: SPA forwarding broke silently.
  await page.goto('/dashboard');
  await expect(page).toHaveURL(/\/login\?returnUrl=/);
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
});
