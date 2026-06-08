# Phase 3 — Frontend Production Parity: Implementation Plan

## Overview

Wire two test suites to close Risks #5 and #7 from the test plan:
- **Risk #5**: a Playwright e2e smoke test against the production JAR proves Angular's prod build, lazy-loaded bundles, SPA forwarding, and auth flow all work the same as `ng serve`
- **Risk #7**: Angular unit tests for `authInterceptor` using Jest prove the concurrent-401 serialization works and that a refresh failure fails queued requests gracefully (not hangs)

## Current State Analysis

- No e2e test directory exists; Playwright is not installed in this repo
- Angular 21 frontend has no test runner configured — only `auth.guard.spec.ts` exists as a dead spec (no runner to execute it)
- `authInterceptor` has a confirmed bug at `auth.interceptor.ts:63-68`: the `catchError` block calls `throwError()` without first calling `refresh$.error()`, so any queued request waiting on `refresh$.pipe(take(1))` hangs indefinitely when refresh fails
- `authService.refresh()` has no `timeout()` operator, so a network hang propagates the same silent-hang bug
- `application-local.yml` at project root has no CORS override — smoke test hitting `http://localhost:8080` is same-origin, so CORS headers are irrelevant

### Key Discoveries

- `auth.interceptor.ts:10-11` — `refresh$` and `refreshInFlight` are module-level constants, not injectable; tests must use `jest.isolateModulesAsync()` to get fresh state per test
- `auth.interceptor.ts:63-68` — confirmed catchError gap: no `refresh$.error()` call
- `src/main/frontend/src/app/core/auth/auth.guard.spec.ts` — existing spec file; passes once Jest is wired
- `angular.json:27-29` — build output goes to `../../../target/classes/static`; Maven packages it into the JAR during `./mvnw clean package`
- `SecurityConfig.java:40-51` — `/*.js` pattern covers root-level `chunk-HASH.js` lazy-load bundles; empirical verification is one goal of the smoke test
- `application-local.yml` lives at project root (not inside `src/main/resources/`); Spring Boot picks it up as external config when JAR starts from the project root with `--spring.profiles.active=local`

## Desired End State

After this plan:
1. `cd src/main/frontend && npm test` runs all Angular unit tests via Jest; 3 interceptor scenarios pass
2. `cd e2e && npx playwright test` builds the prod JAR, starts it, runs the smoke test, and tears it down — all without manual intervention
3. The smoke test proves: lazy bundles resolve, Tailwind/spartan.ui CSS renders, auth flow works, SPA deep-link forwarding works
4. `test-plan.md §6.4` has a filled-in cookbook entry

**Verification**: All three commands exit 0 from a clean clone (with local DB running and `application-local.yml` in place).

## What We're NOT Doing

- Testing every obligations CRUD path in the smoke test (add only; edit/delete are out of scope for smoke)
- Testing `returnUrl` round-trip in the smoke test (login always navigates to `/dashboard`)
- Adding a timeout test scenario for Gap 2 beyond the `timeout()` fix itself
- Addressing Gap 3 (Subject reuse fragility) — works correctly for the success path; documented as known fragility
- Wiring e2e into CI (Phase 4 of the test rollout)

## Implementation Approach

**Risk #7 (unit tests):** TDD sequence — write all 3 scenarios first, run them (scenarios 1+2 should be green, scenario 3 red), then fix the interceptor to make scenario 3 green. This proves the test would have caught the existing bug.

**Risk #5 (e2e smoke):** Playwright at root-level `e2e/` with a `webServer` config that builds the JAR and starts it. A `setup` project runs `auth.setup.ts` (register unique user + save storageState) before the `smoke` project runs `smoke.spec.ts` (already-logged-in smoke flow).

## Critical Implementation Details

**Module-level state in interceptor tests**: `refresh$` (a `Subject<string>`) and `refreshInFlight` are module-level constants in `auth.interceptor.ts` — they persist across tests in the same Jest run. After scenario 3 calls `refresh$.error()`, the Subject is completed and any subsequent test subscribing to it immediately receives that error. Use `jest.isolateModulesAsync()` + dynamic import in `beforeEach` to get a fresh module instance (and thus fresh `refresh$` and `refreshInFlight = false`) for each test.

**`webServer` working directory**: `playwright.config.ts` lives in `e2e/` but `./mvnw` must run from the project root. Set `webServer.cwd` to `path.join(__dirname, '..')` so the Maven command resolves correctly.

**`storageState` path resolution**: Playwright resolves `storageState` paths relative to `playwright.config.ts`. Use `'.auth/user.json'` (relative) and ensure `.auth/` is gitignored. The directory is created on first successful run of `auth.setup.ts`.

---

## Phase 1: Wire Jest + jest-preset-angular

### Overview

Install Jest and configure it as the Angular test runner. Verify the existing `auth.guard.spec.ts` passes as a baseline before writing any new tests.

### Changes Required

#### 1. Install Jest devDependencies

**File**: `src/main/frontend/package.json`

**Intent**: Add Jest, jest-preset-angular, and related packages as devDependencies. Add a `test` script that runs `jest`.

**Contract**: New devDependencies: `jest`, `jest-preset-angular`, `@types/jest`, `jest-environment-jsdom`. New script: `"test": "jest"`. Do not add `ts-jest` separately — `jest-preset-angular` bundles its own transformer.

#### 2. Jest configuration

**File**: `src/main/frontend/jest.config.ts` (new file)

**Intent**: Configure Jest to use `jest-preset-angular`, set `jsdom` as the test environment, and point to the setup file.

**Contract**:
```typescript
import { Config } from 'jest';
export default {
  preset: 'jest-preset-angular',
  setupFilesAfterFramework: ['<rootDir>/src/setup-jest.ts'],
  testEnvironment: 'jsdom',
  transform: {
    '^.+\\.(ts|js|html|svg)$': ['jest-preset-angular', {
      tsconfig: '<rootDir>/tsconfig.spec.json',
    }],
  },
} satisfies Config;
```

#### 3. Jest setup file

**File**: `src/main/frontend/src/setup-jest.ts` (new file)

**Intent**: Bootstrap Angular's test utilities for Jest.

**Contract**: Single line: `import 'jest-preset-angular/setup-jest';`

#### 4. TypeScript config for specs

**File**: `src/main/frontend/tsconfig.spec.json` (new file)

**Intent**: TypeScript config scoped to test files — types set to `jest` (not `jasmine`), includes spec files, extends base `tsconfig.json`.

**Contract**:
```json
{
  "extends": "./tsconfig.json",
  "compilerOptions": {
    "outDir": "./out-tsc/spec",
    "types": ["jest"]
  },
  "files": ["src/setup-jest.ts"],
  "include": ["src/**/*.spec.ts"]
}
```

### Success Criteria

#### Automated Verification

- `cd src/main/frontend && npm install` exits 0
- `cd src/main/frontend && npm test` exits 0
- `auth.guard.spec.ts` passes (2 tests)

#### Manual Verification

- No TypeScript errors in `tsconfig.spec.json`
- Test output names each test case clearly (Jest's default reporter)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Interceptor Unit Tests — TDD RED Phase

### Overview

Write all 3 interceptor unit test scenarios. Scenarios 1 and 2 should be green against the current (unfixed) interceptor. Scenario 3 should be red — this proves the test detects the existing bug.

### Changes Required

#### 1. Interceptor spec file

**File**: `src/main/frontend/src/app/core/auth/auth.interceptor.spec.ts` (new file)

**Intent**: Cover three concurrent-401 scenarios using `HttpTestingController`. Use `jest.isolateModulesAsync()` in `beforeEach` to get fresh module-level `refresh$` and `refreshInFlight` state for every test.

**Contract**: 

TestBed setup pattern (inside each isolated module callback):
```typescript
await TestBed.configureTestingModule({
  providers: [
    provideHttpClient(withInterceptors([authInterceptor])),
    provideHttpClientTesting(),
    { provide: Router, useValue: { navigate: jest.fn() } },
    { provide: TokenStorageService, useValue: mockTokenStorage },
    { provide: AuthService, useValue: mockAuthService },
  ],
}).compileComponents();
httpController = TestBed.inject(HttpTestingController);
http = TestBed.inject(HttpClient);
```

**Scenario 1 — single 401, refresh succeeds:**
- Fire `GET /api/test` → `HttpTestingController` responds 401
- Interceptor calls `authService.refresh()` → flush with `{ accessToken: 'new-token', refreshToken: 'new-refresh' }`
- Interceptor retries original request → flush with 200
- Assert: response received, `Authorization: Bearer new-token` on retry

**Scenario 2 — concurrent 401s, refresh succeeds:**
- Fire two `GET /api/test` requests simultaneously
- Both respond 401
- Assert: `authService.refresh()` called exactly ONCE
- Flush refresh with success
- Both retry requests respond 200
- Assert: both subscribers receive their response

**Scenario 3 — concurrent 401s, refresh fails (expected RED against current code):**
- Fire two requests → both 401
- Flush refresh with an error (e.g., 401 on `/auth/refresh`)
- Assert: BOTH original requests receive an error (not hang)
- Set a `jest.setTimeout(3000)` for this test — if the queued request hangs, the test times out and fails clearly

### Success Criteria

#### Automated Verification

- `npm test` runs all 3 scenarios
- Scenario 1 exits green
- Scenario 2 exits green
- Scenario 3 exits RED (test failure or timeout — expected)

#### Manual Verification

- Jest output clearly identifies scenario 3 as failing with an assertion error (not a cryptic timeout)
- Test names match: `single 401 - refresh succeeds`, `concurrent 401s - refresh succeeds`, `concurrent 401s - refresh fails - queued request receives error`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Fix Interceptor Bug — TDD GREEN Phase

### Overview

Fix the two interceptor gaps: add `refresh$.error()` on refresh failure, and add `timeout()` to the refresh call. All 3 tests must go green after this phase.

### Changes Required

#### 1. Fix catchError — emit error signal to queued subscribers

**File**: `src/main/frontend/src/app/core/auth/auth.interceptor.ts`

**Intent**: In the `catchError` block at lines 63-68, signal all queued subscribers that refresh failed by calling `refresh$.error(refreshErr)` before returning `throwError`. This unblocks any request waiting on `refresh$.pipe(take(1))`.

**Contract**: The fixed catchError block:
```typescript
catchError(refreshErr => {
  refreshInFlight = false;
  refresh$.error(refreshErr);        // ← unblocks queued requests
  tokenStorage.clear();
  router.navigate(['/login']);
  return throwError(() => refreshErr);
})
```

#### 2. Add timeout to refresh call

**File**: `src/main/frontend/src/app/core/auth/auth.interceptor.ts`

**Intent**: Pipe `timeout(10_000)` onto the `authService.refresh(storedRefresh)` call so a network hang cannot cause indefinite suspension. A timeout fires as an error into `catchError`, which (after the fix above) signals queued requests and redirects to `/login`.

**Contract**: Import `timeout` from `rxjs/operators`. Apply it as the first operator: `authService.refresh(storedRefresh).pipe(timeout(10_000), switchMap(...), catchError(...))`.

### Success Criteria

#### Automated Verification

- `npm test` exits 0
- All 3 interceptor scenarios green
- `auth.guard.spec.ts` still passes (no regression)

#### Manual Verification

- `timeout` import added, no TypeScript errors
- No console warnings from jest about open observables or async operations

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Playwright Project Setup

### Overview

Create a root-level `e2e/` directory with Playwright configured to build the JAR, start it, and serve as the system under test. Define two projects: `setup` (runs `auth.setup.ts`) and `smoke` (depends on setup, uses `storageState`).

### Changes Required

#### 1. E2E package

**File**: `e2e/package.json` (new file)

**Intent**: Standalone npm project for e2e tests with Playwright as the only dependency.

**Contract**:
```json
{
  "name": "finance-hq-e2e",
  "private": true,
  "scripts": { "test": "playwright test" },
  "devDependencies": { "@playwright/test": "^1.50.0" }
}
```

Run `cd e2e && npm install && npx playwright install chromium` to install the browser.

#### 2. Playwright config

**File**: `e2e/playwright.config.ts` (new file)

**Intent**: Configure `webServer` to build the prod JAR and start it from the project root. Define a `setup` project (auth fixture) and a `smoke` project (depends on setup, uses storageState). Set `baseURL` to `http://localhost:8080`.

**Contract**:
```typescript
import { defineConfig } from '@playwright/test';
import path from 'path';

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  retries: 0,
  use: { baseURL: 'http://localhost:8080', headless: true },
  webServer: {
    command: './mvnw clean package -DskipTests && java -jar target/finance-hq-*.jar --spring.profiles.active=local',
    cwd: path.join(__dirname, '..'),
    port: 8080,
    reuseExistingServer: false,
    timeout: 180_000,
  },
  projects: [
    { name: 'setup', testMatch: '**/auth.setup.ts' },
    {
      name: 'smoke',
      testMatch: '**/smoke.spec.ts',
      use: { storageState: '.auth/user.json' },
      dependencies: ['setup'],
    },
  ],
});
```

#### 3. Gitignore for e2e artifacts

**File**: `e2e/.gitignore` (new file)

**Intent**: Prevent auth state, test reports, and Playwright output from being committed.

**Contract**: `.auth/`, `test-results/`, `playwright-report/`, `node_modules/`

### Success Criteria

#### Automated Verification

- `cd e2e && npm install && npx playwright install chromium` exits 0
- `cd e2e && npx playwright test --list` shows `auth.setup.ts` and `smoke.spec.ts` (even if they don't exist yet — Playwright lists by project config)

#### Manual Verification

- `playwright.config.ts` has no TypeScript errors
- `.gitignore` covers all Playwright output paths

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 5: Auth Setup Fixture + Smoke Test

### Overview

Write `auth.setup.ts` (registers a unique user, logs in, saves storageState) and `smoke.spec.ts` (the full prod-build smoke flow: dashboard renders, add obligation, sign out, login, deep-link SPA forwarding).

### Changes Required

#### 1. Auth setup fixture

**File**: `e2e/tests/auth.setup.ts` (new file)

**Intent**: Register a fresh user with a timestamp-based email, log in, and save storageState to `.auth/user.json`. This runs as the `setup` project before the smoke test.

**Contract**: Use `test` from `@playwright/test`. After successful login and navigation to `/dashboard`, call `await page.context().storageState({ path: '.auth/user.json' })`. The email must be unique per run: `smoke-${Date.now()}@test.com`.

Navigate to `/register` → fill form → submit → wait for navigation to `/login` → fill login form → submit → wait for URL to contain `/dashboard` → save state.

#### 2. Smoke test

**File**: `e2e/tests/smoke.spec.ts` (new file)

**Intent**: Cover the full 10-step prod-build smoke flow using the storageState from the setup fixture. The test starts already authenticated and covers: dashboard render (lazy bundles + CSS), add obligation, sign out, login, and deep-link SPA forwarding.

**Contract**: Single `test()` block (sequential steps are one logical user journey):

1. `page.goto('/')` → `page.waitForURL('**/dashboard')` — proves SPA routing and auth guard pass
2. `page.getByRole('heading', { name: 'Obligations' })` toBeVisible — proves lazy bundle for `LayoutComponent`/`ObligationsComponent` resolved
3. Compute CSS verification: `page.locator('nav').evaluate(el => getComputedStyle(el).backgroundColor)` — assert value is not `rgba(0, 0, 0, 0)` (transparent), proving Tailwind CSS loaded
4. Empty state or table is visible — proves `GET /api/obligations` fired and response rendered
5. Click "Add obligation" button → dialog opens → fill form → submit → `page.getByText('Obligation added.')` toBeVisible → new row appears in table
6. Click "Sign out" → `page.waitForURL('**/login')` — proves logout clears tokens and redirects
7. `page.goto('/dashboard')` → `page.waitForURL('**/login')` — proves SPA forwarding (`SpaForwardingConfig`) forwarded `/dashboard` to `index.html`, Angular bootstrapped, auth guard fired, redirect issued (this is the deep-link + unauthenticated path)
8. Login form visible (proves Angular bootstrapped correctly from deep-link)

Use `getByRole`, `getByLabel`, `getByText` for all locators. No CSS selectors or XPath.

### Success Criteria

#### Automated Verification

- `cd e2e && npx playwright test` exits 0
- `auth.setup.ts` creates `.auth/user.json`
- `smoke.spec.ts` passes all assertions

#### Manual Verification

- Screenshots in `playwright-report/` show styled login page (not unstyled HTML)
- Dashboard screenshot shows sidebar nav and obligations area with correct layout
- "Obligation added." toast visible in screenshot
- Deep-link navigation screenshot shows `/login` URL

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 6: Update test-plan.md §6.4

### Overview

Fill in the `§6.4 Adding an e2e smoke test for the prod build` cookbook section with the pattern established in Phase 5.

### Changes Required

#### 1. Cookbook entry §6.4

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the "TBD — see §3 Phase 3" placeholder in §6.4 with a concise cookbook entry documenting the auth.setup.ts + smoke.spec.ts pattern, the storageState auth approach, and the webServer JAR lifecycle config.

**Contract**: The entry should describe: (1) project setup in `playwright.config.ts` (setup + smoke projects, storageState dependency), (2) auth fixture pattern (`auth.setup.ts` with timestamp email + storageState save), (3) smoke test structure (storageState start → assertions → sign out → deep-link), (4) CSS verification heuristic (`getComputedStyle` on nav background). Link to `e2e/playwright.config.ts` as reference implementation.

Also update the Phase 3 row in §3 Phased Rollout: set `Status: complete` and `Change folder: testing-frontend-production-parity`.

### Success Criteria

#### Automated Verification

- `context/foundation/test-plan.md` §6.4 section is non-empty (no "TBD" placeholder)

#### Manual Verification

- §3 table shows Phase 3 as `complete`
- §6.4 is self-contained: a new contributor can follow it to add a second smoke test without reading this plan

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests

- 3 interceptor scenarios: single-401 happy path, concurrent-401 success, concurrent-401 failure (all in `auth.interceptor.spec.ts`)
- 2 guard tests: existing `auth.guard.spec.ts` (baseline, must still pass after Jest wiring)
- `jest.isolateModulesAsync()` required for all interceptor tests to prevent module-state bleed

### E2E Tests

- One smoke test covering the complete prod-build risk surface area
- `auth.setup.ts` handles user provisioning (separate from assertions)
- Single Playwright Chromium browser

### Manual Testing Steps

1. Start with a clean database (or at least no conflicting test emails)
2. Run `cd e2e && npx playwright test` — watch JAR boot in webServer output
3. Check `playwright-report/index.html` for screenshots proving CSS rendered
4. Verify the deep-link step: the final screenshot should show `/login` URL in address bar

## References

- Research: `context/changes/testing-frontend-production-parity/research.md`
- Auth interceptor: `src/main/frontend/src/app/core/auth/auth.interceptor.ts:63-68` (bug location)
- SPA forwarding: `src/main/java/com/example/finance_hq/web/SpaForwardingConfig.java`
- Existing guard spec: `src/main/frontend/src/app/core/auth/auth.guard.spec.ts`
- Security config: `src/main/java/com/example/finance_hq/security/SecurityConfig.java:40-51`
- Phase 1 integration patterns: `context/changes/testing-notification-pipeline-reliability/`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Wire Jest + jest-preset-angular

#### Automated

- [x] 1.1 `npm install` exits 0 — ffce779
- [x] 1.2 `npm test` exits 0 — ffce779
- [x] 1.3 `auth.guard.spec.ts` passes (2 tests) — ffce779

#### Manual

- [x] 1.4 No TypeScript errors in `tsconfig.spec.json` — ffce779
- [x] 1.5 Test output names each test case clearly — ffce779

### Phase 2: Interceptor Unit Tests — TDD RED Phase

#### Automated

- [x] 2.1 `npm test` runs all 3 scenarios — 652c05d
- [x] 2.2 Scenario 1 exits green — 652c05d
- [x] 2.3 Scenario 2 exits green — 652c05d
- [x] 2.4 Scenario 3 exits RED (expected) — 652c05d

#### Manual

- [x] 2.5 Jest output clearly identifies scenario 3 as failing — 652c05d
- [x] 2.6 Test names are descriptive and match documented scenario names — 652c05d

### Phase 3: Fix Interceptor Bug — TDD GREEN Phase

#### Automated

- [x] 3.1 `npm test` exits 0
- [x] 3.2 All 3 interceptor scenarios green
- [x] 3.3 `auth.guard.spec.ts` still passes (no regression)

#### Manual

- [ ] 3.4 No TypeScript errors after fix
- [ ] 3.5 No jest warnings about open observables or async operations

### Phase 4: Playwright Project Setup

#### Automated

- [ ] 4.1 `npm install && npx playwright install chromium` exits 0
- [ ] 4.2 `npx playwright test --list` shows setup and smoke projects

#### Manual

- [ ] 4.3 `playwright.config.ts` has no TypeScript errors
- [ ] 4.4 `.gitignore` covers all Playwright output paths

### Phase 5: Auth Setup Fixture + Smoke Test

#### Automated

- [ ] 5.1 `npx playwright test` exits 0
- [ ] 5.2 `auth.setup.ts` creates `.auth/user.json`
- [ ] 5.3 `smoke.spec.ts` passes all assertions

#### Manual

- [ ] 5.4 Screenshots show styled pages (not unstyled HTML)
- [ ] 5.5 "Obligation added." toast visible in screenshot
- [ ] 5.6 Deep-link screenshot shows `/login` URL

### Phase 6: Update test-plan.md §6.4

#### Automated

- [ ] 6.1 §6.4 section is non-empty (no "TBD" placeholder)

#### Manual

- [ ] 6.2 Phase 3 row in §3 shows `complete`
- [ ] 6.3 §6.4 is self-contained for a new contributor
