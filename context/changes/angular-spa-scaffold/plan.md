# Angular SPA Scaffold (F-03) Implementation Plan

## Overview

Scaffold an Angular SPA at `src/main/frontend/` with HTML5 routing, Angular Material as the UI toolkit, a JWT-aware HTTP interceptor (auto-refresh-once-then-redirect), and an auth guard. The SPA is built into the Spring Boot JAR via `frontend-maven-plugin` and served at `/` with deep-link fallback. This delivers the F-03 roadmap foundation — stub screens with full auth plumbing — so S-01 can build the user-visible register/login flow on top of a ready harness.

## Current State Analysis

- **Frontend: absent.** `src/main/frontend/` does not exist; `src/main/resources/static/` is empty.
- **Backend auth is shipped (F-02).** `AuthController` (`src/main/java/com/example/finance_hq/auth/AuthController.java`) exposes `POST /auth/{register,login,refresh,logout}`. `TokenResponse` (`src/main/java/com/example/finance_hq/auth/dto/TokenResponse.java:1`) returns `{ accessToken, refreshToken, tokenType: "Bearer", expiresIn }`. `JwtAuthenticationFilter` expects `Authorization: Bearer <token>` on every protected request.
- **CORS is pre-wired** for `http://localhost:4200` (`src/main/resources/application.properties:30`, `src/main/java/com/example/finance_hq/security/SecurityConfig.java:57`). No CORS changes needed for dev.
- **Production build is Docker-only**, single Maven stage (`Dockerfile`). Any `mvn package` work integrates automatically.
- **Error response shape is the legacy `{ "error": "...", "details": [...] }`.** RFC 7807 migration is queued (`context/foundation/lessons.md:46`); interceptor parsing must be encapsulated so the swap is a one-file change.
- **No frontend testing exists.** Backend uses JUnit Jupiter + Testcontainers.

## Desired End State

After this plan ships:
- Running `./mvnw clean package` produces an executable JAR that, when launched, serves `http://localhost:8080/` returning the Angular SPA shell.
- The SPA has three stub screens (`/login`, `/register`, `/dashboard`), a Material toolbar with an "Sign out" action, an auth guard redirecting unauthenticated users from `/dashboard` to `/login`, and an HTTP interceptor that attaches Bearer tokens and refreshes once on 401.
- Reloading `http://localhost:8080/dashboard` returns `index.html` (HTML5 deep-link fallback); reloading `http://localhost:8080/main-XXXX.js` returns the actual JS bundle.
- `ng serve --proxy-config proxy.conf.json` from `src/main/frontend/` runs the dev server on `:4200` proxying `/auth/**` to `:8080` for hot-reload development.
- A Vitest smoke spec verifies the auth guard redirect; a MockMvc test verifies `/dashboard` forwards to `/index.html`.

### Key Discoveries:

- **Angular 21 ships Vitest as the default `ng test` runner** (Angular docs, `angular.dev/roadmap`) — `ng new` produces a Vitest-ready project; no Karma swap needed.
- **`frontend-maven-plugin` 2.0.0 is current**; standard pattern is `install-node-and-npm` → `npm ci` (generate-resources) → `npm run build` (prepare-package), with `installDirectory` set to `target/` so `node_modules` and the Node install do not pollute the frontend workspace.
- **Angular application builder supports flattened `outputPath`** via `{ "base": "<path>", "browser": "" }`, dropping the `browser/` subfolder so files land directly in Spring's `static/` resource root.
- **CORS already trims and accepts only `Content-Type, Authorization` headers** (`SecurityConfig.java:60-66`); no extra dev wiring required for the chosen `localStorage` + Bearer strategy.
- **`AuthController` returns 401 with body `{ "error": "Invalid or expired refresh token" }` on bad refresh** (`GlobalExceptionHandler.java:23-25`) — the interceptor must distinguish this 401 from a protected-resource 401 to avoid infinite-loop refreshes.
- **Default Spring Boot static handler serves `classpath:/static/`** as `/**` — a `WebMvcConfigurer` view controller with the pattern `/{path:[^\\.]*}` won't collide with static asset requests (they contain a dot) or with `/auth/**` (covered by the registered `@RestController`).

## What We're NOT Doing

- **No working login/register UI.** Forms are placeholder components only; visible field rendering, validation messages, and post-submit navigation are S-01's scope.
- **No SSR / hydration.** `--ssr=false` at scaffold time; deferred to v1.1 if ever.
- **No obligation feature code** — that is S-02.
- **No RFC 7807 client-side parsing** — interceptor parses today's `{ error, details }` shape; swap is queued behind the backend migration (`lessons.md:46`).
- **No PWA / service-worker / offline support** — out of scope for MVP.
- **No CI changes to GitHub Actions YAML beyond what Maven already does.** The build hook is via `frontend-maven-plugin`, so existing `./mvnw clean package` in CI Just Works.
- **No httpOnly-cookie auth refactor.** Tokens live in `localStorage`; XSS-hardening is flagged for v1.1.
- **No new API endpoints.** The backend contract is frozen for F-03.
- **No production CDN.** SPA is served by Spring Boot from the JAR — Cloudflare Pages in front of Railway is parked.

## Implementation Approach

Four phases, each independently reviewable and ending in a verifiable build:

1. **Workspace + Maven integration** — bring up the Angular project, wire it into `./mvnw clean package`. Locks the build contract first; everything else is content inside an already-working harness.
2. **SPA shell + routing + stubs** — establish the Material app shell, route table, and stub feature components. No auth code yet.
3. **Auth plumbing** — `TokenStorageService`, `AuthService`, functional `authInterceptor` (with auto-refresh), `authGuard`. Pure plumbing; no UI changes beyond binding the guard to `/dashboard`.
4. **Spring SPA fallback + end-to-end smoke** — Spring `WebMvcConfigurer` for deep-link forward, one Vitest spec, one MockMvc spec, and end-to-end manual reload check.

## Critical Implementation Details

- **SPA fallback exclusion via dot-rule.** The fallback pattern `/{path:[^\\.]*}` matches segments without a dot. Static assets (e.g., `main-AB12.js`, `styles.css`, `favicon.ico`) all contain a dot and bypass the forwarder, falling through to the default `ResourceHttpRequestHandler`. Registered controller routes (`/auth/**`, `/actuator/**`) win over view-controller mappings, so no explicit exclusion list is required. This is load-bearing — getting the regex wrong silently breaks static asset loading after deploy.

- **Interceptor refresh-loop safety.** The interceptor must (a) skip the Bearer header and refresh logic for requests to `/auth/login`, `/auth/register`, and `/auth/refresh` to avoid recursion, (b) treat a 401 from `/auth/refresh` itself as terminal (clear storage + redirect, do not retry), and (c) serialize concurrent refreshes through a `BehaviorSubject<string | null>` so a burst of N protected requests during expiry triggers exactly one refresh call. Without the BehaviorSubject queue, a page load that fires several parallel HTTP calls will issue N parallel refreshes, each invalidating the previous refresh token.

## Phase 1: Angular workspace + Maven build integration

### Overview

Stand up the Angular project under `src/main/frontend/`, install Angular Material, and wire `frontend-maven-plugin` so `./mvnw clean package` produces a JAR containing the built SPA at `classpath:/static/`. End state: a default `ng new` app loads at `http://localhost:8080/`.

### Changes Required:

#### 1. Angular workspace scaffold

**File**: `src/main/frontend/` (new directory tree generated by Angular CLI)

**Intent**: Use the Angular CLI to scaffold a fresh standalone-component workspace with HTML5 routing, SCSS styling, and no SSR. Run interactively once; commit the result.

**Contract**: Run `cd src/main && npx -y @angular/cli@latest new finance-hq --directory frontend --routing --style=scss --ssr=false --skip-git --strict --package-manager=npm`. Project name: `finance-hq`. Standalone components mode (default in Angular 17+). After scaffold, add Angular Material via `cd src/main/frontend && ng add @angular/material --theme=indigo-pink --typography --animations enabled` accepting defaults.

#### 2. Angular build output flattened into Spring static root

**File**: `src/main/frontend/angular.json`

**Intent**: Configure the `build` target to write the production bundle directly into `target/classes/static/` (so it lands in the JAR's `classpath:/static/`) with no `browser/` subfolder.

**Contract**: Under `projects.finance-hq.architect.build.options`, set `outputPath` to the object form so Spring serves files at `/`, not `/browser/`:

```json
"outputPath": {
  "base": "../../../target/classes/static",
  "browser": ""
}
```

Also set `"baseHref": "/"` and `"deployUrl"` unset. Leave `index: "src/index.html"`, `browser: "src/main.ts"`, and the default polyfills intact.

#### 3. Dev-server proxy for backend calls

**File**: `src/main/frontend/proxy.conf.json` (new)

**Intent**: Forward `/auth/**` requests from `ng serve` (`:4200`) to the Spring Boot dev server (`:8080`) so the SPA can hit the real backend during development without touching CORS.

**Contract**: A single proxy mapping for `/auth/*` to `http://localhost:8080`, `changeOrigin: true`, `secure: false`. Reference it from `angular.json` under `serve.options.proxyConfig` so `ng serve` picks it up automatically.

#### 4. `frontend-maven-plugin` wiring

**File**: `pom.xml`

**Intent**: Add `frontend-maven-plugin` so Maven downloads Node, installs deps, and runs the Angular production build during the standard package lifecycle. `./mvnw clean package` produces a JAR containing the SPA; `./mvnw spring-boot:run` rebuilds it when invoked from `generate-resources`.

**Contract**: Inside `<build><plugins>`, add `com.github.eirslett:frontend-maven-plugin:2.0.0` configured with `workingDirectory=src/main/frontend`, `installDirectory=target`. Executions:
- `install-node-and-npm` (phase `generate-resources`) — Node `v22.20.0`, npm `11.0.0`
- `npm-ci` (phase `generate-resources`) — `arguments=ci`
- `npm-build` (phase `prepare-package`) — `arguments=run build`, env `NODE_ENV=production`

Vitest is NOT bound to the Maven `test` phase in this iteration — the backend test suite stays the gate for `./mvnw test`. Frontend tests run via `npm test` inside the workspace.

#### 5. `.gitignore` update

**File**: `.gitignore`

**Intent**: Ignore Angular generated files and the `node_modules` directory under the frontend workspace.

**Contract**: Append entries for `src/main/frontend/node_modules/`, `src/main/frontend/.angular/`, `src/main/frontend/dist/` (in case a developer runs `ng build` outside Maven), and `src/main/frontend/coverage/`.

#### 6. README / contributor note

**File**: `HELP.md`

**Intent**: Document the two dev workflows side-by-side: backend-only (`./mvnw spring-boot:run`) and full-stack with hot reload (`ng serve` proxied to a running backend). Three to five lines, no more.

**Contract**: Append a short "Frontend development" subsection with the two commands and a note that production-style verification is `./mvnw clean package && java -jar target/*.jar`.

### Success Criteria:

#### Automated Verification:

- `./mvnw clean package -DskipTests` completes successfully
- The produced JAR (`target/finance-hq-*.jar`) contains `BOOT-INF/classes/static/index.html` (verify with `jar tf`)
- `cd src/main/frontend && npm test -- --run` exits 0 (Vitest, default spec from `ng new`)
- `cd src/main/frontend && npx ng lint` exits 0 (default ESLint config from `ng new`)

#### Manual Verification:

- `java -jar target/finance-hq-*.jar` boots; visiting `http://localhost:8080/` shows the default Angular welcome page
- `cd src/main/frontend && ng serve --proxy-config proxy.conf.json` brings up `:4200` with the same welcome page; a manual `curl http://localhost:4200/auth/login -X POST` is proxied to the backend (returns 400 for missing body — not connection refused)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation that `./mvnw clean package` succeeds and `java -jar` serves the SPA shell before proceeding.

---

## Phase 2: SPA shell + routing + stub components

### Overview

Replace the default `ng new` welcome page with a Material toolbar shell, a route table covering `/login`, `/register`, `/dashboard`, `/` (redirect), and `**` (not-found), and a placeholder standalone component for each route. No auth code yet — `/dashboard` is reachable by anyone.

### Changes Required:

#### 1. App shell with Material toolbar

**File**: `src/main/frontend/src/app/app.component.ts`, `app.component.html`, `app.component.scss`

**Intent**: Replace the scaffolded welcome content with a top-bar layout: a `<mat-toolbar>` showing the app name on the left and a "Sign out" `<button mat-button>` on the right (no-op for now), with `<router-outlet>` filling the remaining viewport.

**Contract**: Standalone `AppComponent`, imports `MatToolbarModule`, `MatButtonModule`, `RouterOutlet`. The "Sign out" button calls a method that will be wired in Phase 3. Inline template kept short (≤30 lines); styles set the toolbar to `position: sticky; top: 0`.

#### 2. Route table

**File**: `src/main/frontend/src/app/app.routes.ts`

**Intent**: Declare the five routes; lazy-load each feature component to keep the initial chunk small.

**Contract**: Default export of `Routes` array with: `{ path: '', pathMatch: 'full', redirectTo: 'dashboard' }`, `{ path: 'login', loadComponent: () => import('./features/login/login.component').then(m => m.LoginComponent) }`, same for `register` and `dashboard`, `{ path: '**', loadComponent: () => import('./features/not-found/not-found.component').then(m => m.NotFoundComponent) }`. The `dashboard` route's `canActivate` is left empty here; it gains `[authGuard]` in Phase 3.

#### 3. App config wires router with HTML5 location strategy

**File**: `src/main/frontend/src/app/app.config.ts`

**Intent**: Confirm the bootstrap config uses `provideRouter` with default `withComponentInputBinding()` (already from `ng new --routing`) and Material's `provideAnimationsAsync()`. Add `provideHttpClient(withInterceptors([]))` — empty interceptor list now; populated in Phase 3.

**Contract**: The exported `appConfig: ApplicationConfig` is the canonical providers root. Order of providers: router → animations → http-client.

#### 4. Stub feature components (login, register, dashboard, not-found)

**Files**:
- `src/main/frontend/src/app/features/login/login.component.ts` (+ `.html`, `.scss`)
- `src/main/frontend/src/app/features/register/register.component.ts` (+ `.html`, `.scss`)
- `src/main/frontend/src/app/features/dashboard/dashboard.component.ts` (+ `.html`, `.scss`)
- `src/main/frontend/src/app/features/not-found/not-found.component.ts` (+ `.html`, `.scss`)

**Intent**: One standalone component per route. Each renders a centered `<mat-card>` with the page name as an `<h1>` and a single sentence of placeholder copy. No forms, no inputs, no logic.

**Contract**: All four use `MatCardModule`. Selector follows `app-<route>`. No external state; no inputs; no outputs.

#### 5. Environment configuration

**Files**: `src/main/frontend/src/environments/environment.ts`, `environment.development.ts`

**Intent**: Hold a single config object exposing `apiBaseUrl`. Dev points to `''` (relative, picked up by the dev-server proxy); prod points to `''` (relative, same-origin under Spring Boot).

**Contract**: `export const environment = { production: <bool>, apiBaseUrl: '' }`. The empty string makes both build targets use relative URLs (e.g., `POST /auth/login`), letting the dev proxy handle local routing and same-origin handle production. Wire `fileReplacements` in `angular.json` so the `development` configuration swaps in `environment.development.ts`.

### Success Criteria:

#### Automated Verification:

- `./mvnw clean package -DskipTests` succeeds with the new component tree
- `cd src/main/frontend && npx ng build` produces a bundle ≤ 800 KB initial-load (`ng build` budget warnings cause non-zero exit if exceeded)
- `cd src/main/frontend && npm test -- --run` exits 0

#### Manual Verification:

- `ng serve` shows the toolbar across all routes
- Navigating `/login`, `/register`, `/dashboard` all render their placeholder cards under the toolbar
- Navigating to `/anything-else` shows the not-found component
- Browser back/forward buttons work; URL updates without page reload
- Lighthouse accessibility audit on `/login` returns ≥ 90 (sanity check that Material defaults are accessible)

**Implementation Note**: After this phase, pause for manual confirmation that the four routes render correctly and the toolbar layout is acceptable before proceeding.

---

## Phase 3: Auth plumbing (interceptor, refresh, guard)

### Overview

Add the auth harness with no UI changes: `TokenStorageService` (localStorage wrapper), `AuthService` (typed methods calling backend), `authInterceptor` (functional, with auto-refresh-once), and `authGuard` (functional `CanActivateFn`). Wire the guard to `/dashboard`; wire the interceptor into `appConfig`. The sign-out button in the toolbar now clears storage and navigates to `/login`.

### Changes Required:

#### 1. Token storage service

**File**: `src/main/frontend/src/app/core/auth/token-storage.service.ts`

**Intent**: Thin wrapper around `localStorage` so the storage strategy can be swapped (e.g., to httpOnly cookies in v1.1) by touching one file. Encapsulates two keys and provides typed accessors.

**Contract**: `@Injectable({ providedIn: 'root' })`. Public API:
- `setTokens(accessToken: string, refreshToken: string): void`
- `getAccessToken(): string | null`
- `getRefreshToken(): string | null`
- `clear(): void`

Keys: `fhq.accessToken`, `fhq.refreshToken`. No expiry tracking (server enforces).

#### 2. Auth service skeleton

**File**: `src/main/frontend/src/app/core/auth/auth.service.ts`

**Intent**: Typed HTTP calls to the four backend auth endpoints, persisting tokens on success. No UI orchestration here — just network + storage.

**Contract**: `@Injectable({ providedIn: 'root' })`. Inject `HttpClient` and `TokenStorageService`. Public API returning Observables:
- `register(req: { email: string; password: string }): Observable<void>` → `POST /auth/register` (201, no body)
- `login(req: { email: string; password: string }): Observable<TokenResponse>` → `POST /auth/login`; on success calls `tokenStorage.setTokens(...)` via `tap`
- `refresh(refreshToken: string): Observable<TokenResponse>` → `POST /auth/refresh`; on success updates storage
- `logout(): Observable<void>` → `POST /auth/logout` with the stored refresh token; in `finalize` calls `tokenStorage.clear()` regardless of success/failure
- `isAuthenticated(): boolean` → `!!tokenStorage.getAccessToken()`

`TokenResponse` typed as `{ accessToken: string; refreshToken: string; tokenType: 'Bearer'; expiresIn: number }` matching the backend record.

#### 3. Functional HTTP interceptor with auto-refresh

**File**: `src/main/frontend/src/app/core/auth/auth.interceptor.ts`

**Intent**: Attach Bearer header on protected requests; on 401 from a protected endpoint, refresh once and replay; on refresh failure or refresh-endpoint 401, clear storage and redirect to `/login`. Serialize concurrent refreshes through a module-scoped `BehaviorSubject` so a burst of N parallel requests triggers exactly one refresh.

**Contract**: Exported `authInterceptor: HttpInterceptorFn`.

```typescript
const AUTH_BYPASS_PATHS = ['/auth/login', '/auth/register', '/auth/refresh'];
const refresh$ = new BehaviorSubject<string | null>(null);
let refreshInFlight = false;
```

Flow:
1. If `req.url` matches a bypass path → forward unchanged.
2. Else attach `Authorization: Bearer <access>` if a token exists.
3. On `HttpErrorResponse` with `status === 401`:
   - If the failing URL is `/auth/refresh` itself → `tokenStorage.clear()`, navigate `/login`, rethrow.
   - Else if `refreshInFlight` → wait on `refresh$.pipe(filter(t => !!t), take(1))`, then replay with the new token.
   - Else set `refreshInFlight = true`, call `authService.refresh(storedRefresh)`. On success: emit the new access token on `refresh$`, replay original; on failure: clear storage, navigate `/login`, rethrow.
4. All other errors pass through untouched.

Register in `appConfig` via `provideHttpClient(withInterceptors([authInterceptor]))`.

#### 4. Functional auth guard

**File**: `src/main/frontend/src/app/core/auth/auth.guard.ts`

**Intent**: Gate `/dashboard` (and future protected routes) by checking for a stored access token. Redirect unauthenticated users to `/login` with a `returnUrl` query param so post-login navigation can route back.

**Contract**: Exported `authGuard: CanActivateFn`. Returns `true` if `tokenStorage.getAccessToken()` is non-null; else returns a `UrlTree` for `/login?returnUrl=<state.url>` via `Router.parseUrl()`.

Apply to the dashboard route in `app.routes.ts`: `{ path: 'dashboard', canActivate: [authGuard], loadComponent: ... }`.

#### 5. Sign-out wiring in toolbar

**File**: `src/main/frontend/src/app/app.component.ts`

**Intent**: The toolbar "Sign out" button now calls `authService.logout()` and routes to `/login`. The button is hidden when `authService.isAuthenticated()` is false.

**Contract**: Add a method `signOut()` that calls `authService.logout().subscribe({ complete: () => router.navigate(['/login']) })`. Bind `*ngIf="authService.isAuthenticated()"` (or signal-based equivalent) on the button. Use Angular's `inject()` for the service references.

### Success Criteria:

#### Automated Verification:

- `./mvnw clean package -DskipTests` succeeds
- `cd src/main/frontend && npx ng build` succeeds with no type errors
- `cd src/main/frontend && npm test -- --run` exits 0 (default Vitest spec still passes; no new specs required this phase)

#### Manual Verification:

- With backend running, navigate to `/dashboard` from a fresh browser tab → guard redirects to `/login?returnUrl=%2Fdashboard`
- Manually set `localStorage.fhq.accessToken = "fake"` in DevTools; reload `/dashboard` → page renders (guard passes)
- Open Network tab, trigger any protected request from the console (e.g., `fetch('/auth/me')` — once that endpoint exists in S-01) and confirm `Authorization: Bearer fake` header is attached
- Clear localStorage and verify guard redirects again

**Implementation Note**: After this phase, pause for manual confirmation that guard redirect and Bearer attachment both work before proceeding.

---

## Phase 4: Spring SPA fallback + production smoke

### Overview

Add a `WebMvcConfigurer` so Spring Boot forwards SPA deep links (e.g., `/dashboard`) to `/index.html` after a hard reload, and add two thin smoke tests: one Vitest spec for the guard redirect, one MockMvc spec for the fallback. End-to-end manual verification: build, run the JAR, reload a deep link, confirm Angular handles routing.

### Changes Required:

#### 1. SPA forwarding configuration

**File**: `src/main/java/com/example/finance_hq/web/SpaForwardingConfig.java` (new package `web`)

**Intent**: Register a single view controller mapping that forwards any path segment without a dot to `/index.html`. Paths containing a dot (e.g., `main-AB12.js`, `favicon.ico`) bypass the forwarder and are served by Spring's default static handler. Registered `@RestController` routes (`/auth/**`, `/actuator/**`) take precedence over view controllers, so no explicit exclusion list is required.

**Contract**: `@Configuration` class implementing `WebMvcConfigurer`. Override `addViewControllers(ViewControllerRegistry registry)` to register two patterns: `/{path:[^\\.]*}` and `/**/{path:[^\\.]*}`, both forwarding to `forward:/index.html`. The two-pattern form covers future nested SPA routes (e.g., `/dashboard/obligations` in S-02) without touching this file.

#### 2. Backend smoke test for SPA fallback

**File**: `src/test/java/com/example/finance_hq/web/SpaForwardingConfigTest.java` (new)

**Intent**: Verify (a) `GET /dashboard` returns the `index.html` body (or forwards to it), (b) `GET /auth/login` is NOT forwarded (controller still owns it — returns 405 for GET on a POST-only endpoint, not 200 HTML), (c) `GET /main-XX.js` is NOT forwarded (404 if the asset is absent in test, but explicitly not the SPA shell).

**Contract**: `@SpringBootTest(webEnvironment = MOCK)` + `@AutoConfigureMockMvc`. Three test methods. The "returns index.html" assertion uses `MockMvcResultMatchers.forwardedUrl("/index.html")` against `/dashboard`.

#### 3. Frontend smoke test for auth guard

**File**: `src/main/frontend/src/app/core/auth/auth.guard.spec.ts` (new)

**Intent**: Verify the guard returns a `UrlTree` for `/login` when no token is present and `true` when a token is present.

**Contract**: Vitest spec using Angular's `TestBed` with `provideRouter([])`. Two test cases:
- "redirects to /login when no token" — stubs `TokenStorageService.getAccessToken` to return `null`; asserts the guard returns a `UrlTree` whose `toString()` includes `/login` and `returnUrl=%2Fdashboard`.
- "allows activation when token is present" — stubs `getAccessToken` to return `'fake'`; asserts the guard returns `true`.

### Success Criteria:

#### Automated Verification:

- `./mvnw test` passes (new `SpaForwardingConfigTest` included)
- `cd src/main/frontend && npm test -- --run` passes (new `auth.guard.spec.ts` included)
- `./mvnw clean package` produces a JAR; `jar tf target/finance-hq-*.jar | grep 'static/index.html'` finds the file

#### Manual Verification:

- `java -jar target/finance-hq-*.jar` boots; visit `http://localhost:8080/` → SPA renders, toolbar visible
- Direct-load `http://localhost:8080/login` → SPA renders the login stub (deep-link fallback works)
- Reload from `/dashboard` → guard redirects to `/login` (no Spring error page)
- `curl -sI http://localhost:8080/main-*.js` returns `200 OK` with `Content-Type: application/javascript` (not HTML)
- `curl -sI http://localhost:8080/auth/login -X GET` returns `405 Method Not Allowed` (controller wins; not a 200 HTML page)

**Implementation Note**: After this phase, pause for the end-to-end manual confirmation that all deep links, asset URLs, and API endpoints behave correctly under the production JAR before declaring F-03 complete.

---

## Testing Strategy

### Unit Tests:

- **Frontend (Vitest)**: One smoke spec on the auth guard (`auth.guard.spec.ts`). Interceptor and AuthService specs are deferred to S-01, where the full login flow exercises them under realistic conditions.
- **Backend (JUnit Jupiter + MockMvc)**: One smoke spec on the SPA fallback (`SpaForwardingConfigTest`).

### Integration Tests:

- The existing `AuthControllerIntegrationTest` continues to run unchanged — F-03 does not touch the auth flow.

### Manual Testing Steps:

1. `./mvnw clean package` — verify build success and SPA bundled in JAR.
2. `java -jar target/finance-hq-*.jar` — verify root URL renders Angular shell.
3. Navigate through `/login`, `/register`, `/dashboard` via browser links and confirm each stub renders.
4. Reload on `/dashboard` — confirm `index.html` is served (no 404).
5. Open DevTools → Network — confirm a navigation request to `/dashboard` returns `200` with `text/html`, while `/main-*.js` returns `200` with `application/javascript`.
6. Clear `localStorage` and reload `/dashboard` → guard redirects to `/login?returnUrl=%2Fdashboard`.
7. Manually inject `localStorage.setItem('fhq.accessToken', 'fake')` in DevTools → reload `/dashboard` → page renders.
8. `cd src/main/frontend && ng serve --proxy-config proxy.conf.json` (with backend on `:8080`) → confirm hot-reload dev workflow and proxy correctly forwards `/auth/**`.

## Performance Considerations

- Initial bundle target ≤ 800 KB (Angular default budget). With Material + Angular core, the welcome shell is typically ~500 KB gzipped — well within budget.
- `frontend-maven-plugin` adds ~30 seconds to the first cold build (Node download); subsequent builds reuse `target/node/` and complete in seconds. Docker layer caching in the existing Dockerfile preserves this between deploys provided `pom.xml` and `package-lock.json` are unchanged.
- JVM heap cap (`-Xmx384m`) in the Dockerfile remains adequate — serving static SPA assets from a JAR is a low-allocation path.

## Migration Notes

- No data migration. No backend contract change. No CORS change.
- Existing F-02 integration tests are untouched.
- The first `./mvnw clean package` after this change downloads Node (~50 MB) into `target/node/`; CI cache for `target/node/` (if introduced) speeds subsequent builds. For Railway deploys, the Docker build runs `mvn clean package` afresh each time — Node download cost is paid on every deploy until a multi-stage Docker cache for the frontend stage is added (out of scope here).

## References

- Roadmap entry: `context/foundation/roadmap.md:92` (F-03)
- PRD requirement: `context/foundation/prd.md` (NFR — latest two major browsers)
- Tech stack: `context/foundation/tech-stack.md` (Angular SPA, served as static assets)
- Backend auth contract: `src/main/java/com/example/finance_hq/auth/AuthController.java`, `dto/TokenResponse.java:1`
- CORS config: `src/main/java/com/example/finance_hq/security/SecurityConfig.java:57`
- Legacy error shape (queued for RFC 7807 migration): `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java`, `context/foundation/lessons.md:46`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Angular workspace + Maven build integration

#### Automated

- [x] 1.1 `./mvnw clean package -DskipTests` completes successfully — c89b261
- [x] 1.2 Produced JAR contains `BOOT-INF/classes/static/index.html` — c89b261
- [x] 1.3 `npm test -- --run` exits 0 in `src/main/frontend` — c89b261
- [x] 1.4 `npx ng lint` exits 0 in `src/main/frontend` — c89b261

#### Manual

- [x] 1.5 `java -jar target/finance-hq-*.jar` serves the default Angular welcome page at `http://localhost:8080/` — c89b261
- [x] 1.6 `ng serve --proxy-config proxy.conf.json` proxies `/auth/**` to the backend (not connection-refused) — c89b261

### Phase 2: SPA shell + routing + stub components

#### Automated

- [x] 2.1 `./mvnw clean package -DskipTests` succeeds with the new component tree — d193780
- [x] 2.2 `npx ng build` initial bundle is within the 800 KB budget — d193780
- [x] 2.3 `npm test -- --run` exits 0 — d193780

#### Manual

- [x] 2.4 Toolbar renders across all routes — d193780
- [x] 2.5 `/login`, `/register`, `/dashboard` each render their placeholder cards — d193780
- [x] 2.6 Unknown route shows the not-found component — d193780
- [x] 2.7 Browser back/forward updates URL without a page reload — d193780
- [x] 2.8 Lighthouse accessibility audit on `/login` returns ≥ 90 — d193780

### Phase 3: Auth plumbing (interceptor, refresh, guard)

#### Automated

- [x] 3.1 `./mvnw clean package -DskipTests` succeeds
- [x] 3.2 `npx ng build` succeeds with no type errors
- [x] 3.3 `npm test -- --run` exits 0

#### Manual

- [ ] 3.4 Fresh tab → `/dashboard` redirects to `/login?returnUrl=%2Fdashboard`
- [ ] 3.5 Setting `localStorage.fhq.accessToken` lets `/dashboard` render
- [ ] 3.6 Outbound request includes `Authorization: Bearer <token>`
- [ ] 3.7 Clearing storage restores the guard redirect

### Phase 4: Spring SPA fallback + production smoke

#### Automated

- [ ] 4.1 `./mvnw test` passes (including `SpaForwardingConfigTest`)
- [ ] 4.2 `npm test -- --run` passes (including `auth.guard.spec.ts`)
- [ ] 4.3 JAR contains `static/index.html`

#### Manual

- [ ] 4.4 `java -jar target/finance-hq-*.jar` serves the SPA at `/`
- [ ] 4.5 Direct-load `/login` renders the login stub
- [ ] 4.6 Reload from `/dashboard` redirects via guard (no Spring error page)
- [ ] 4.7 `curl -sI /main-*.js` returns `200` with `application/javascript`
- [ ] 4.8 `curl -sI -X GET /auth/login` returns `405` (controller still owns it)
