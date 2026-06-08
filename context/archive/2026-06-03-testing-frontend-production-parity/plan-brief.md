# Phase 3 — Frontend Production Parity: Plan Brief

> Full plan: `context/changes/testing-frontend-production-parity/plan.md`
> Research: `context/changes/testing-frontend-production-parity/research.md`

## What & Why

Wire two test suites to close Risks #5 and #7 from the test plan. Risk #5: Angular's prod build (served by Spring Boot) can diverge silently from `ng serve` — lazy bundles, CSS, SPA routing, and auth flow must be verified against the actual JAR. Risk #7: the `authInterceptor` has a confirmed bug where a refresh failure leaves queued requests hanging indefinitely — unit tests prove the fix.

## Starting Point

No e2e framework exists; Angular has no test runner configured (zero spec files running). `auth.interceptor.ts:63-68` has a live bug: `catchError` does not call `refresh$.error()`, so any request queued behind a failing refresh hangs until page reload. `auth.guard.spec.ts` exists but can't run without a test runner.

## Desired End State

`npm test` (in `src/main/frontend/`) runs 5 unit tests (3 interceptor + 2 guard) and exits 0. `npx playwright test` (in `e2e/`) builds the prod JAR, starts it, registers a fresh user, exercises the full auth+obligations+deep-link flow in a real browser, and exits 0. `test-plan.md §6.4` has a filled-in cookbook entry.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Interceptor fix sequence | TDD: write failing test first, then fix | Proves the test would catch the original bug — fixes that arrive before tests can pass without catching anything | Plan |
| Angular test runner | Jest + jest-preset-angular | Aligns with Angular team's direction post-Karma deprecation; no headless browser needed for unit tests | Plan |
| E2E location | Root-level `e2e/` | E2E tests the full stack, not just Angular; separate from frontend unit tests | Plan |
| JAR lifecycle | Playwright `webServer` config | Built-in Playwright API; handles build+start+teardown without custom globalSetup scripts | Plan |
| Test user provisioning | `storageState` via setup project | Register once per run, reuse across smoke test assertions; self-contained, no pre-seeded data required | Plan |
| Smoke test scope | Full 10-step flow | Covers all prod-divergence surface area: lazy bundles, CSS, auth, CRUD dialog, sign out, deep-link | Plan |

## Scope

**In scope:**
- Jest wiring + jest-preset-angular setup
- Interceptor unit tests (3 scenarios, TDD)
- `refresh$.error()` + `timeout(10_000)` fix to interceptor
- Root-level `e2e/` with Playwright + Chromium
- `auth.setup.ts` (register + storageState) + `smoke.spec.ts` (full prod-build smoke)
- `test-plan.md §6.4` cookbook entry

**Out of scope:**
- Edit/delete obligations CRUD in smoke test
- `returnUrl` round-trip after login
- CI wiring (Phase 4 of test rollout)
- Subject reuse refactor (Gap 3 — works correctly, deferred)
- Timeout scenario unit test (fix ships, test deferred)

## Architecture / Approach

Two independent test layers. **Unit layer**: Jest in `src/main/frontend/` with `jest.isolateModulesAsync()` to reset module-level `refresh$` state between tests — without this, scenario 3 (refresh failure) corrupts the Subject for subsequent tests. **E2E layer**: Playwright in `e2e/` using `webServer` to build+start the prod JAR from the project root, a `setup` project that registers a unique user and saves storageState, and a `smoke` project that starts authenticated and walks the full UI flow.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Wire Jest | Test runner working; `auth.guard.spec.ts` as green baseline | jest-preset-angular version mismatch with Angular 21 |
| 2. Interceptor tests (RED) | 3 scenarios written; scenario 3 red (expected) | Module-level state bleed if `isolateModules` not used |
| 3. Fix interceptor (GREEN) | `refresh$.error()` + `timeout(10_000)` added; all 3 green | Fix must not break scenario 1/2 (regression risk) |
| 4. Playwright setup | `e2e/` with webServer+storageState config; JAR boots | Maven build time ~90s; JAR startup adds ~20s |
| 5. Smoke test | Full 10-step flow passes in real browser | Dialog component lazy-load may require Security allowlist fix if chunk-HASH.js not covered by `/*.js` |
| 6. Update test-plan §6.4 | Cookbook entry filled in | — |

**Prerequisites:** Local PostgreSQL running; `application-local.yml` at project root; `./mvnw` available (Java 21 on PATH)
**Estimated effort:** ~3-4 focused sessions across 6 phases

## Open Risks & Assumptions

- **Chunk file security allowlist**: `SecurityConfig`'s `/*.js` should cover `chunk-HASH.js` — confirmed by research analysis but empirically verified only when Phase 5 smoke test passes lazy-loaded routes
- **JAR build time**: `webServer` timeout set to 180s; Maven cold build may approach this on a slow machine — adjust if needed
- **Database state**: smoke test registers a unique user per run; created obligations persist in local DB (no cleanup); acceptable for smoke testing, not for integration testing

## Success Criteria (Summary)

- `npm test` exits 0 with 5 passing tests (3 interceptor + 2 guard)
- `npx playwright test` exits 0 with smoke flow verified in real Chromium
- Screenshots in `playwright-report/` confirm styled pages and "Obligation added." toast
