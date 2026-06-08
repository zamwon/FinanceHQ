---
date: 2026-06-03T11:02:53Z
researcher: Blazej Karnecki
git_commit: fb6b89e
branch: master
repository: FinanceHQ
topic: "Testing Frontend Production Parity — Phase 3"
tags: [research, frontend, angular, playwright, e2e, auth-interceptor, spa, tailwind, production-build]
status: complete
last_updated: 2026-06-03
last_updated_by: Blazej Karnecki
---

# Research: Testing Frontend Production Parity

**Date**: 2026-06-03T11:02:53Z
**Researcher**: Blazej Karnecki
**Git Commit**: fb6b89e
**Branch**: master
**Repository**: FinanceHQ

## Research Question

What is the precise state of the Angular frontend's production build pipeline, SPA serving, auth interceptor, and routing, so that `/10x-plan` can design an effective Phase 3 test suite covering Risk #5 (prod build diverges from dev) and Risk #7 (token refresh race condition)?

## Summary

Phase 3 covers two risks with very different test layers:

**Risk #5 (prod build divergence)** — requires e2e Playwright smoke against the production JAR. The Angular build pipeline is correctly configured (esbuild, `inlineCritical: false`, `postcss.config.json`, `styles.css`, no `tailwind.config.js`), resolving the prior spartan.ui/Tailwind v4 production burn. SPA forwarding via `SpaForwardingConfig` and static asset security in `SecurityConfig` are in place and have an existing unit test. The remaining gap is that no browser automation smoke test exists to verify the complete app works when the JAR is started — lazy-load bundle resolution, deep-link reload, auth redirect, and CSS rendering all require a live browser against the prod build.

**Risk #7 (refresh race condition)** — has a specific, unfixed code gap in the auth interceptor. The `refresh$` Subject never emits when token refresh fails; any request queued waiting on it hangs indefinitely. This requires a unit test (mocked HttpClient, no browser). Zero interceptor unit tests exist today.

---

## Detailed Findings

### 1. Angular Build Pipeline

**File**: `src/main/frontend/angular.json`

- **Output path**: `../../../target/classes/static` (line 27–29) — builds directly into Spring Boot's static resource root during `./mvnw clean package`
- **Default configuration**: `production` (line 37) — prod build is the default
- **Optimization**: enabled with `minifyStyles: true` (lines 59–63)
- **`inlineCritical: false`** (line 62) — prevents Angular from emitting `<link media="print" onload="this.media='all'">` which would be blocked by the `script-src 'self'` CSP
- **Output hashing**: `"all"` (line 58) — all bundles get content-hash suffixes (e.g., `main-LIURI4EZ.js`, `styles-23OA4NHG.css`)
- **Source maps**: absent in prod, enabled in dev (line 69)
- **File replacement**: `src/environments/environment.ts` → `src/environments/environment.development.ts` in dev only (lines 70–75)

**Angular version**: `^21.2.0` — esbuild-based builder (`@angular/build ^21.2.12`), NOT webpack. This matters because Angular 21's esbuild pipeline handles AOT, tree-shaking, and CSS processing differently from webpack.

**CSS framework** (`src/main/frontend/src/styles.css`):
- `@import "tailwindcss"` via `@tailwindcss/postcss ^4.3.0`
- `@import "@spartan-ng/brain/hlm-tailwind-preset.css"` (spartan.ui)
- Uses `@source "../.."` directive

**PostCSS** (`src/main/frontend/postcss.config.json`):
- JSON format (not `.js`) — required by Angular's `@angular/build`; `.js` is silently ignored
- Plugin: `@tailwindcss/postcss` only

**No `tailwind.config.js`** — correct for Tailwind v4; its presence would cause Angular to attempt v3 plugin mode and fail.

**Environment files** (`src/main/frontend/src/environments/`):
- Both `environment.ts` (prod) and `environment.development.ts` have `apiBaseUrl: ''` — same-origin API calls in both environments. No URL divergence.

**Dev proxy** (`src/main/frontend/proxy.conf.json`):
- `/auth/*` → `http://localhost:8080` and `/api/*` → `http://localhost:8080`
- Active **only** during `ng serve`. In the prod JAR, Spring Boot handles both `/auth/**` and `/api/**` directly — no proxy layer.

**All components lazy-loaded** (app.routes.ts):
- `LoginComponent`, `RegisterComponent`, `LayoutComponent`, `ObligationsComponent`, `NotFoundComponent`
- Lazy-load bundle resolution failures produce blank pages with no console errors in prod — a silent failure mode

**Maven integration** (`pom.xml` lines 142–185):
- `frontend-maven-plugin 2.0.0` runs `npm run build` during `prepare-package` phase
- Bundles Angular output directly into the JAR at `BOOT-INF/classes/static/`

---

### 2. Spring Boot SPA Forwarding + Static Asset Serving

**SPA forwarding** (`src/main/java/com/example/finance_hq/web/SpaForwardingConfig.java`):
```java
registry.addViewController("/{path:[^\\.]*}").setViewName("forward:/index.html");
registry.addViewController("/**/{path:[^\\.]*}").setViewName("forward:/index.html");
```
- Regex `[^\\.]*` = path segment with no dot → SPA routes get `index.html`, static files (`.js`, `.css`) bypass the forwarder
- Covers root routes (`/dashboard`) and nested future routes (`/dashboard/obligations`)
- Controllers (`/auth/**`, `/api/**`) take Spring MVC routing precedence and are never forwarded

**Existing test**: `src/test/java/com/example/finance_hq/web/SpaForwardingConfigTest.java`
- `/dashboard` → forwards to `/index.html` ✓
- `/auth/login` GET → 405 (controller wins) ✓
- `/main-AB12.js` → 404 (not forwarded — has dot) ✓

**Static asset security** (`src/main/java/com/example/finance_hq/security/SecurityConfig.java` lines 40–51):
```java
.requestMatchers(HttpMethod.GET, "/*.js", "/*.js.map", "/*.css", "/*.css.map",
    "/*.ico", "/*.png", "/*.svg", "/*.woff", "/*.woff2", "/*.ttf", "/assets/**").permitAll()
```
Explicit extension-based allowlist — no `PathRequest.toStaticResources()` (removed in Spring Boot 4.x per `context/foundation/lessons.md`).

**SPA shell routes permitted without auth**:
`/`, `/index.html`, `/login`, `/register`, `/dashboard` (line 46)
Comment: "Angular guard handles client-side auth checks" — the guard runs after Spring forwards to `index.html`

**CSP header** (SecurityConfig lines 34–37):
```
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
img-src 'self' data:; font-src 'self'
```
- `unsafe-inline` on `style-src` — required for Angular's runtime style injection
- No `unsafe-inline` on `script-src` — compatible with `inlineCritical: false`
- No external CDN or font sources

**CORS** (`application.properties`): `${CORS_ALLOWED_ORIGINS:http://localhost:4200}` — Railway overrides in prod. Smoke test against JAR on localhost needs this set or must match origin.

---

### 3. Auth Interceptor + Token Refresh (Risk #7)

**Files**:
- `src/main/frontend/src/app/core/auth/auth.interceptor.ts`
- `src/main/frontend/src/app/core/auth/auth.service.ts`
- `src/main/frontend/src/app/core/auth/token-storage.service.ts`
- `src/main/frontend/src/app/core/auth/auth.guard.ts`

**Token storage** (`token-storage.service.ts`):
- `localStorage` keys: `fhq.accessToken`, `fhq.refreshToken`
- `clear()` removes both on logout or refresh failure

**Interceptor concurrent-401 serialization** (`auth.interceptor.ts` lines 10–11):
```typescript
const refresh$ = new Subject<string>();
let refreshInFlight = false;
```

Flow:
1. Request receives 401 → check `refreshInFlight`
2. If `false`: set `refreshInFlight = true`, call `authService.refresh()`, on success: set false, broadcast via `refresh$.next(newToken)`, retry; on failure: set false, clear tokens, navigate `/login`
3. If `true`: subscribe to `refresh$.pipe(take(1), switchMap(newToken => retry with newToken))`

**Gap 1 — queued requests hang on refresh failure** (UNFIXED):

When refresh fails, the `catchError` block (lines 63–68):
```typescript
catchError(() => {
  refreshInFlight = false;
  tokenStorage.clear();
  router.navigate(['/login']);
  return throwError(...);
})
```
...resets the flag, clears tokens, and redirects — but **never calls `refresh$.next()` or `refresh$.error()`**. Any request already waiting on `refresh$.pipe(take(1))` (lines 42–45) never receives a value and hangs indefinitely. The user is navigated to `/login` but the pending HTTP observable never completes, potentially leaving the app in a partially broken state.

**Gap 2 — no timeout on refresh call**:
`authService.refresh()` (auth.service.ts line 29) has no `timeout()` operator. A network hang causes indefinite suspension of queued requests.

**Gap 3 — Subject reuse**:
`refresh$` is a module-level constant — it is never recreated between refresh cycles. After a first successful refresh, if a second refresh cycle begins, the `refresh$` is the same Subject. Since `take(1)` completes the subscriber after one emission, subsequent cycles work — but this is fragile and not obvious.

**Historical context note**: The `refresh$` was intentionally changed from `BehaviorSubject<string | null>` to `Subject<string>` during the angular-spa-scaffold impl-review (F3) to eliminate stale-value replay (BehaviorSubject would replay the last token to new subscribers after refresh). The Subject-based pattern is correct for the success path but leaves the failure path incomplete.

**No unit tests for the interceptor**:
- `src/main/frontend/src/app/core/auth/auth.guard.spec.ts` exists (49 lines) — tests redirect on missing token, allow on token present
- **No `auth.interceptor.spec.ts`** — zero coverage for 401 handling, refresh, or concurrent scenarios

**Auth guard** (`auth.guard.ts` lines 6–15):
- Only checks token *presence* in localStorage — does not validate expiry or signature
- On failure: redirects to `/login?returnUrl=<encoded-original-url>` with `returnUrl` query param
- Expired but present token passes the guard → hits API → gets 401 → interceptor handles it

---

### 4. Application Routing + UI Flows

**Route structure** (`src/main/frontend/src/app/app.routes.ts`):
```
/           → redirectTo: 'dashboard'
/login      → LoginComponent (lazy, public)
/register   → RegisterComponent (lazy, public)
/dashboard  → ObligationsComponent (lazy, guarded by authGuard via LayoutComponent)
/**         → NotFoundComponent (lazy)
```

**Critical:** The `authGuard` is applied at the layout wrapper level. When an unauthenticated user hits `/dashboard`, the guard fires, reads no token, and redirects to `/login?returnUrl=%2Fdashboard`.

**Login component** (`src/main/frontend/src/app/features/login/login.component.ts`):
- Form: `email` (required, email format) + `password` (required)
- Submit → `POST /auth/login` → stores tokens → navigate `/dashboard`
- 401 error → "Invalid email or password."
- Other error → "Something went wrong. Please try again."

**Register component** (`src/main/frontend/src/app/features/register/register.component.ts`):
- Form: `email` + `password` (8+ chars, uppercase, digit, special char) + `confirmPassword`
- Password strength hints shown on focus
- Submit → `POST /auth/register` → navigate `/login`
- 409 → "An account with this email already exists."

**Obligations component** (`src/main/frontend/src/app/features/obligations/obligations.component.ts`):
- `ngOnInit` → `GET /api/obligations` → renders table or empty state
- Edit → `ObligationDialogComponent` → `PATCH /api/obligations/{id}` (amount + paymentDay only)
- Delete → `DeleteDialogComponent` → `DELETE /api/obligations/{id}`
- Add → `ObligationDialogComponent` → `POST /api/obligations`
- Toast: "Obligation added." / "Obligation updated." / "Obligation deleted."

**Layout + sidebar** (`src/main/frontend/src/app/shared/layout/`):
- "Obligations" nav link → `/dashboard`
- Dark mode toggle (persists in localStorage as 'theme' key)
- Sign out → `POST /auth/logout` → clear tokens → navigate `/login`

**API endpoints required by smoke test**:
| Method | Path | Auth |
|--------|------|------|
| POST | `/auth/register` | None |
| POST | `/auth/login` | None |
| POST | `/auth/logout` | Bearer |
| POST | `/auth/refresh` | None (refresh token in body) |
| GET | `/api/obligations` | Bearer |
| POST | `/api/obligations` | Bearer |
| PATCH | `/api/obligations/{id}` | Bearer |
| DELETE | `/api/obligations/{id}` | Bearer |

---

### 5. Prod Build vs. Dev — Divergence Surface Area

| Dimension | Dev (`ng serve`) | Prod (JAR) | Risk |
|-----------|-----------------|------------|------|
| Build tool | esbuild dev-server | esbuild AOT optimized | AOT fails on code that works JIT |
| API routing | Proxy (`proxy.conf.json`) | Spring Boot handles `/auth/*`, `/api/*` directly | CORS config must match |
| Lazy-load bundles | Served by dev-server | `chunk-*.js` files in `BOOT-INF/classes/static/` | A bundle not in the Security allowlist → 403 |
| CSS processing | PostCSS via dev-server | PostCSS via `@angular/build` → `styles-HASH.css` | Style rule purging if content scanning misconfigured |
| CSS delivery | Inline or separate | Separate file (inlineCritical: false) | File must be in Security allowlist |
| `inlineCritical` | N/A | `false` | Was `true` by default; would block CSS under strict CSP |
| Environment | `environment.development.ts` | `environment.ts` | apiBaseUrl: '' in both — no divergence |
| SPA deep-links | Dev-server handles all routes | `SpaForwardingConfig` regex handles non-file paths | Regex bug → 404 on deep links |
| Auth guard returnUrl | Present | Present | Must verify redirect happens correctly |

**Chunk files and Spring Security**: Angular 21's lazy-loading generates `chunk-HASH.js` files at the root of the static directory. The current Security allowlist covers `/*.js` — this pattern matches root-level chunk files. No gap identified, but should be verified empirically with the smoke test.

---

### 6. What the Smoke Test Must Prove (Risk #5 Oracle)

From test plan §2 Risk Response Guidance:

> "Angular prod build served by Spring Boot renders login, allows login, shows dashboard, navigates to obligations — same flow as `ng serve`"

Must challenge:
> "`ng build` succeeds" does not mean "built app works." Tree-shaking, AOT, CSS processing, SPA forwarding can each break silently.

**Minimum viable smoke test flow**:
1. Start production JAR (`java -jar target/finance-hq-*.jar --spring.profiles.active=local`)
2. Browser → `http://localhost:8080/` → expect redirect chain → `/login` (unauthenticated)
3. Verify login page renders with visible styling (spartan.ui buttons/inputs styled)
4. Register a new user (or use known credentials)
5. Login → expect redirect to `/dashboard`
6. Verify dashboard layout renders (sidebar, "Obligations" heading)
7. Verify `GET /api/obligations` fires and obligations list or empty state renders
8. Add one obligation — verify POST → toast "Obligation added." → row appears in table
9. Sign out → verify tokens cleared → redirect to `/login`
10. Navigate directly to `http://localhost:8080/dashboard` (deep link) → expect redirect to `/login` (guard + SPA forwarding)

**What this proves**: lazy-load bundles resolve, CSS processes correctly, SPA forwarding works, auth flow works end-to-end, API routing works without proxy.

### 7. What the Unit Test Must Prove (Risk #7 Oracle)

From test plan §2:

> "Two concurrent 401s trigger only one refresh-token request; both original requests retry with new token; session continues"

Must challenge:
> "If first refresh fails, second queued request must also fail gracefully — not retry in a loop"

**Minimum viable unit test scenarios** (mocked HttpClient, no browser):

1. **Happy path — single 401**: Request returns 401 → refresh called once → original request retried with new token → succeeds
2. **Concurrent 401s — refresh succeeds**: Two parallel requests both return 401 → only ONE call to `/auth/refresh` → both original requests retried with new token → both succeed
3. **Concurrent 401s — refresh fails**: Two parallel requests both return 401 → only ONE call to `/auth/refresh` → refresh returns error → tokens cleared → BOTH original requests receive error (not hang) → user navigated to `/login` once (not twice)

**Gap to fix before test can be green**: The `catchError` block must call `refresh$.error(err)` so queued requests receive the error signal. Without this, scenario 3 hangs indefinitely.

---

## Code References

- `src/main/frontend/angular.json:27-30` — output path `../../../target/classes/static`
- `src/main/frontend/angular.json:58,62` — outputHashing: "all", inlineCritical: false
- `src/main/frontend/angular.json:66-76` — dev configuration with file replacement + source maps
- `src/main/frontend/postcss.config.json` — JSON format, `@tailwindcss/postcss`
- `src/main/frontend/src/styles.css` — Tailwind v4 import + spartan.ui preset
- `src/main/frontend/src/environments/environment.ts` — prod env, `apiBaseUrl: ''`
- `src/main/frontend/src/environments/environment.development.ts` — dev env, `apiBaseUrl: ''`
- `src/main/frontend/proxy.conf.json` — dev-only proxy for `/auth` and `/api`
- `src/main/java/com/example/finance_hq/web/SpaForwardingConfig.java` — SPA deep-link regex
- `src/test/java/com/example/finance_hq/web/SpaForwardingConfigTest.java` — existing SPA forwarding unit test
- `src/main/java/com/example/finance_hq/security/SecurityConfig.java:40-51` — static asset allowlist + SPA shell routes
- `src/main/java/com/example/finance_hq/security/SecurityConfig.java:34-37` — CSP header
- `src/main/frontend/src/app/core/auth/auth.interceptor.ts:10-11` — `Subject<string>` + `refreshInFlight` flag
- `src/main/frontend/src/app/core/auth/auth.interceptor.ts:41-68` — 401 handler with serialization logic
- `src/main/frontend/src/app/core/auth/auth.interceptor.ts:63-68` — catchError gap (no `refresh$.error()`)
- `src/main/frontend/src/app/core/auth/auth.service.ts:29-33` — refresh method (no timeout)
- `src/main/frontend/src/app/core/auth/token-storage.service.ts:9-10` — localStorage keys
- `src/main/frontend/src/app/core/auth/auth.guard.ts:6-15` — presence-only token check
- `src/main/frontend/src/app/core/auth/auth.guard.spec.ts` — existing guard tests
- `src/main/frontend/src/app/app.routes.ts` — full lazy-loaded route structure
- `src/main/frontend/src/app/features/obligations/obligations.component.ts:28-38` — ngOnInit → load()
- `src/main/resources/application.properties` — `${PORT:8080}`, `${CORS_ALLOWED_ORIGINS:http://localhost:4200}`

## Architecture Insights

1. **Same-origin architecture**: Both dev and prod use `apiBaseUrl: ''`. Dev uses a proxy to forward `/auth/*` and `/api/*` to localhost:8080. Prod serves everything from one origin. No URL substitution needed between environments — the biggest source of env divergence is absent.

2. **Lazy-load is the prod-only risk surface**: Every route is lazy-loaded. Dev-server serves chunks on-demand from memory; prod JAR serves them from the classpath. A chunk not in the Security allowlist, or a bundle whose path pattern doesn't match `/*.js`, would silently 404 and render a blank page. Only an actual browser request against the JAR can catch this.

3. **inlineCritical: false is a deliberate CSP safety choice**: Angular's default `inlineCritical: true` generates inline `<link>` handlers that need `unsafe-hashes` in `script-src`. This project's CSP is `script-src 'self'` — incompatible with that approach. `inlineCritical: false` was set intentionally to maintain CSP strictness. This is a known pattern from the spartan.ui production burn (see Historical Context).

4. **Refresh Subject failure gap is a real user-visible bug**: If any network condition causes the refresh call to fail while another request is queued, the queued request hangs until the page is reloaded. The user sees the navigation to `/login` but any `HttpClient` call that was mid-flight is orphaned. For the single-tab, light-traffic v0.1 use case this is unlikely to surface, but it IS the race condition Risk #7 describes.

5. **Auth guard redirects with `returnUrl`**: After successful login, `LoginComponent` currently always navigates to `/dashboard` (line 31-32 of login.component.ts), ignoring the `returnUrl` query param. This means deep-link protection (user bookmarks `/dashboard`, opens browser, gets redirected to `/login`, logs in, lands on `/dashboard` anyway) works correctly but not for other guarded routes added in the future.

## Historical Context (from prior changes)

- `context/archive/2026-05-28-spartan-ui-prod-styling/research.md` — The canonical production burn: spartan.ui styled in `ng serve` but invisible in prod build. Root causes: `postcss.config.js` (JS, ignored by Angular), `styles.scss` (SCSS, PostCSS ran before Tailwind), `tailwind.config.js` present (triggered v3 plugin mode). All three fixed; current codebase reflects the fixed state.

- `context/archive/2026-05-27-angular-spa-scaffold/plan.md:268` — BehaviorSubject → Subject migration decision. "Without the BehaviorSubject queue, a page load that fires several parallel HTTP calls will issue N parallel refreshes, each invalidating the previous refresh token." BehaviorSubject was replaced by Subject to eliminate stale-token replay. The success path works correctly; the failure-path gap (queued requests hanging) was not addressed in that review.

- `context/archive/2026-05-27-angular-spa-scaffold/reviews/impl-review.md:F3` — Impl-review finding that triggered the Subject migration. Documents the BehaviorSubject stale-value risk and the fix applied.

- `context/archive/2026-05-27-angular-spa-scaffold/plan.md:57` — SPA forwarding regex documented as "load-bearing — getting the regex wrong silently breaks static asset loading after deploy."

- `context/archive/2026-05-25-f-02-auth-scaffold/reviews/impl-review.md:F3` — Backend refresh token race condition (two concurrent refresh calls creating two valid tokens). Fixed with `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `findByTokenForUpdate()`. This is the backend side of the same family of bugs as the frontend interceptor gap.

## Related Research

- `context/changes/testing-notification-pipeline-reliability/` — Phase 1 patterns: parameterized unit tests, `@SpringBootTest` + Testcontainers integration test shape
- `context/archive/2026-06-02-testing-data-integrity-access-control/` — Phase 2 patterns: IDOR boundary tests, two-user setup

## Open Questions

1. **Does Spring Security's `/*.js` pattern cover `chunk-HASH.js` files produced by Angular 21's lazy-loading?** Angular 21 with esbuild generates named chunk files at the root of the static directory. The `/*.js` pattern in SecurityConfig should match these, but this must be verified empirically by starting the JAR and navigating to a lazy-loaded route.

2. **CORS in smoke test setup**: The smoke test will run against `http://localhost:8080`. `application-local.properties` may need `cors.allowed-origins=http://localhost:8080` (not just `4200`). Verify what the local profile sets for this property before writing the test.

3. **Should the interceptor fix (refresh$.error() on failure) be part of Phase 3 or a separate fix?** The unit test for Risk #7 scenario 3 will fail against the current code. The plan for Phase 3 needs to decide: fix the interceptor first (TDD), or write the test first as a red test and then fix. Either is valid; the plan should make this explicit.

4. **What test user credentials are available in the local test environment?** The Playwright smoke test needs to log in. Can it register a fresh user as part of the test setup, or does it need a pre-seeded test account?

5. **Does `ObligationDialogComponent` require obligations to already exist, or can the smoke test create one?** The research shows add/edit/delete all work through a dialog. A self-contained smoke test that registers → logs in → adds obligation → edits it → deletes it → logs out is fully possible without pre-seeded data.
