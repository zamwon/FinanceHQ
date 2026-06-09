# Sentry Error Monitoring Implementation Plan

## Overview

Wire Sentry error and warning tracking to both the Spring Boot backend and Angular frontend. All browser-side events are routed through a backend tunnel endpoint (`POST /sentry-tunnel`) so the existing strict `default-src 'self'` CSP stays intact without modification.

## Current State Analysis

- Zero monitoring today: only Spring Actuator `/actuator/health` is exposed
- Backend uses SLF4J/Logback with `@RestControllerAdvice` for global exceptions and scattered `log.error()`/`log.warn()` calls in `NotificationService`
- Frontend uses `provideBrowserGlobalErrorListeners()` but has no centralised error reporting — each component handles errors with local UI state
- Deployment: Railway with env var injection; GitHub Actions runs only a post-deploy health check (build lives in Railway's Dockerfile)
- **Hard constraint**: SecurityConfig CSP `default-src 'self'` blocks outbound browser connections to `sentry.io`; the tunnel resolves this

## Desired End State

- Every `log.warn()` and `log.error()` in Spring Boot surfaces in the `finance-hq-backend` Sentry project
- Every unhandled Angular exception and `console.warn()`/`console.error()` surfaces in the `finance-hq-frontend` Sentry project
- Email alerts to blazej.karnecki@gmail.com fire on new issues (first occurrence, not every event)
- Angular stack traces show TypeScript file names and line numbers (not minified bundle output)
- User email addresses are scrubbed from all Sentry event payloads before leaving the app
- Local dev sends nothing; Railway production sends everything

### Key Discoveries

- `io.sentry:sentry-spring-boot-4-starter` — dedicated Spring Boot 4.x artifact (distinct from the Boot 3 starter `sentry-spring-boot-starter`); using the wrong one causes Jakarta EE 11 classloading failures
- `@sentry/angular` v10.27.0+ supports Angular 21; v10.52.0 is latest as of May 2026
- Angular output: `angular.json` outputPath `{'base': '../../../target/classes/static', 'browser': ''}` → maps land at `target/classes/static/*.js.map` (project root)
- `environment.ts` IS the production environment; `environment.development.ts` is the dev override (confirmed via `fileReplacements` in `angular.json`)
- Angular production config has no `sourceMap` key — defaults to false; must explicitly enable as hidden
- `@analogjs/vite-plugin-angular` in devDeps is for vitest only — production build uses `ng build` (Angular esbuild Application Builder)
- GitHub Actions `deploy.yml` has no build step; all building happens in Railway via Dockerfile — source map upload requires a separate `npm run build` in CI

## What We're NOT Doing

- No performance tracing (`tracesSampleRate=0.0`) — free plan, single-user app, errors only
- No Sentry session tracking — noise for a single-user app
- No reporting from local dev
- No release tracking on the backend — informational only, not needed for stack trace symbolication
- No CSP relaxation — the tunnel makes it unnecessary
- No external rate-limiting library — DSN allow-list validation is the primary abuse protection; a simple in-memory counter covers the rest

## Implementation Approach

Five phases in sequence. Phases 2–4 can be developed in parallel once Phase 1 DSNs are in hand, but Phase 4 depends on Phase 3's tunnel URL being deployed before testing end-to-end. Phase 5 requires Phase 4 to set the `sentryRelease` field in environment files.

## Critical Implementation Details

**Spring Boot 4 artifact name**: Use `io.sentry:sentry-spring-boot-4-starter`, NOT `io.sentry:sentry-spring-boot-starter`. The standard starter targets Spring Boot 3.x (Javax) and breaks at startup under Spring Boot 4.x (Jakarta EE 11).

**Sentry SDK self-disables on empty DSN**: When `SENTRY_DSN` env var is absent or empty, the Spring Boot Sentry auto-configuration exits cleanly — no exceptions, no network calls. Same behaviour in the Angular SDK when `sentryDsn` is `''`. This makes the production-only strategy trivial to maintain.

**Tunnel DSN allow-list**: The tunnel controller must validate the incoming envelope's DSN against a hardcoded allow-list containing exactly the frontend DSN. Without this check the endpoint can proxy events for arbitrary Sentry projects.

---

## Phase 1: Sentry Project Setup

### Overview

Manual setup: create two Sentry projects, obtain both DSNs, configure first-occurrence email alerts. No code changes in this phase.

### Changes Required

#### 1. Create `finance-hq-backend` Sentry project

**Platform**: Java → Spring Boot

**Intent**: Isolated project for backend Java events — keeps JVM stack traces separate from Angular JS errors.

**Contract**: After creation, copy the generated DSN. It will be set as `SENTRY_DSN` in Railway in Phase 2.

#### 2. Create `finance-hq-frontend` Sentry project

**Platform**: JavaScript → Angular

**Intent**: Isolated project for browser events.

**Contract**: After creation, copy the generated DSN. It will be hard-coded into `environment.ts` in Phase 4 (frontend DSNs are intentionally public — they are always visible in the browser bundle).

#### 3. Configure alert rules (both projects)

**Intent**: Receive an email on every new issue, not on every repeated event.

**Contract**: In Sentry dashboard for each project: Alerts → Create Alert Rule → Condition: "A new issue is created" → Action: "Send an email to blazej.karnecki@gmail.com". Set "Issue frequency" to "First occurrence" so repeated events don't flood the inbox.

### Success Criteria

#### Manual Verification

- Both `finance-hq-backend` and `finance-hq-frontend` projects exist in Sentry dashboard
- Both DSNs are copied and stored for Phases 2 and 4
- Alert rules are active on both projects and show "New Issue → Email" action

---

## Phase 2: Backend (Spring Boot) SDK Integration

### Overview

Add `sentry-spring-boot-4-starter`, configure minimum event level to `WARN` (captures both warnings and errors), add a `BeforeSendCallback` to scrub email addresses from event payloads, and inject the DSN as a Railway env var.

### Changes Required

#### 1. Add Sentry dependency

**File**: `pom.xml`

**Intent**: Pull in the Spring Boot 4-specific Sentry auto-configuration. The `-4-` infix artifact targets Jakarta EE 11 class names; the standard Boot 3 artifact will fail at runtime.

**Contract**: Add under `<dependencies>`:
```xml
<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry-spring-boot-4-starter</artifactId>
    <version>8.26.0</version>
</dependency>
```
Check [mvnrepository.com/artifact/io.sentry/sentry-spring-boot-4-starter](https://mvnrepository.com/artifact/io.sentry/sentry-spring-boot-4-starter) at implementation time and use the latest stable patch.

#### 2. Configure Sentry properties

**File**: `src/main/resources/application.properties`

**Intent**: Configure DSN (from env var with empty fallback so local dev self-disables), minimum event level, environment tag, and zero performance tracing.

**Contract**: Append these properties following the existing env-var-with-fallback pattern already used in this file:
```properties
sentry.dsn=${SENTRY_DSN:}
sentry.minimum-event-level=WARN
sentry.environment=${SENTRY_ENVIRONMENT:production}
sentry.release=${SENTRY_RELEASE:unknown}
sentry.traces-sample-rate=0.0
```

#### 3. PII scrubbing callback

**File**: `src/main/java/com/example/finance_hq/monitoring/SentryBeforeSendCallback.java`

**Intent**: Strip email addresses from Sentry events before they leave the JVM. The `NotificationService` log messages include the user's email (e.g. `"Failed to send notification to user@example.com: …"`) which must not reach Sentry's servers.

**Contract**: Implement `SentryOptions.BeforeSendCallback` and annotate with `@Component` — the Sentry Spring Boot starter auto-detects it. Apply a regex replacement (`[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}` → `[scrubbed]`) to: the event's formatted message, each breadcrumb message, and each exception value string. Return the modified event; never return `null` (that would suppress the event entirely).

#### 4. Set Railway environment variables

**Intent**: Supply the backend DSN and environment tag to the production JVM.

**Contract**: In Railway dashboard → project → Variables, add:
- `SENTRY_DSN` = `<backend DSN from Phase 1>`
- `SENTRY_ENVIRONMENT` = `production`

### Success Criteria

#### Automated Verification

- `./mvnw test` passes — Sentry SDK must not interfere with the Testcontainers integration test context
- App starts locally with `./mvnw spring-boot:run --spring.profiles.active=local` without Sentry-related errors (empty DSN → SDK disables itself silently)

#### Manual Verification

- Deploy to Railway; in application logs confirm `SentryOptions` initialises (log line: `Sentry initialized` or similar)
- Trigger a deliberate `log.warn("test warning")` call (e.g. from an obligation API call with a test request that hits a known warn path); verify event appears in `finance-hq-backend` Sentry project within 60 seconds
- In the Sentry event detail, confirm no `@` email addresses appear in message, breadcrumbs, or exception values

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Sentry Tunnel Endpoint

### Overview

Add `POST /sentry-tunnel` — a Spring Boot endpoint that validates incoming Sentry envelopes against a DSN allow-list and proxies them to `sentry.io`. This is the only mechanism that allows the Angular app to reach Sentry while keeping `default-src 'self'` in the CSP.

### Changes Required

#### 1. Tunnel controller

**File**: `src/main/java/com/example/finance_hq/web/SentryTunnelController.java`

**Intent**: Safely proxy Angular Sentry envelopes to `sentry.io` without acting as an open proxy for arbitrary projects.

**Contract**:
- `POST /sentry-tunnel` accepts `Content-Type: application/x-sentry-envelope` (or `text/plain`) with the raw envelope body
- Parse the first newline-delimited line as JSON; extract the `dsn` field
- Validate the `dsn` against a hardcoded `Set<String>` allow-list containing exactly the frontend DSN from Phase 1. Return `400 Bad Request` for any unrecognised DSN
- Extract the Sentry project ID from the DSN path segment (format: `https://key@sentry.io/{projectId}`)
- Forward the full raw request body (no modification) to `https://sentry.io/api/{projectId}/envelope/` using `RestClient` with the same `Content-Type` header
- Return the HTTP status received from Sentry to the caller; absorb non-2xx responses gracefully (log at WARN, return 200 to avoid leaking Sentry internals to the browser)
- Implement a global in-memory fixed-window rate limiter: max 100 requests per minute using `AtomicLong` (request count) + `AtomicLong` (window-start timestamp). Return `429 Too Many Requests` when the window is exceeded. Reset on the next minute boundary

#### 2. Register in SecurityConfig

**File**: `src/main/java/com/example/finance_hq/security/SecurityConfig.java`

**Intent**: Allow unauthenticated POST to `/sentry-tunnel`. Angular sends events from unauthenticated pages (login, register error paths) so the endpoint must not require a JWT.

**Contract**: Add `"/sentry-tunnel"` to the existing `requestMatchers(...).permitAll()` block that already lists `/auth/**` and `/actuator/health`. The existing CSRF-disabled, stateless session policy already covers this endpoint.

### Success Criteria

#### Automated Verification

- `./mvnw test` passes
- Integration test: `POST /sentry-tunnel` with a body whose first line DSN is not in the allow-list → 400
- Integration test: `POST /sentry-tunnel` with 101 rapid requests from the same client → the 101st returns 429

#### Manual Verification

- Send a valid Sentry envelope (copy a real envelope from the Angular DevTools Network tab after Phase 4 is deployed) to `POST /sentry-tunnel` on Railway; confirm Sentry receives the event in `finance-hq-frontend`
- Confirm the browser never makes a direct request to `sentry.io` (check Network tab → filter by `sentry.io`)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3 Fix: Test Isolation

### Overview

Patch two test-environment failures discovered after Phase 3 implementation. The controller's production behaviour is correct; the issue is test isolation — shared singleton rate-limiter state and an unhandled empty-body edge case that Spring surfaces as a 500.

### Changes Required

#### 1. Add `resetRateLimiter()` to controller

**File**: `src/main/java/com/example/finance_hq/web/SentryTunnelController.java`

**Intent**: Give tests a way to reset the in-memory rate limiter before each run so the `testTunnelRateLimiting_Returns429OnExceed` test (which fires 100 requests) does not exhaust the window for tests that run afterwards in the same singleton context.

**Contract**: Add after the constructor (line 42):
```java
public void resetRateLimiter() {
    requestCount.set(0);
    windowStart.set(System.currentTimeMillis());
}
```

#### 2. Add empty-body guard before rate-limiter logic

**File**: `src/main/java/com/example/finance_hq/web/SentryTunnelController.java`

**Intent**: Return 400 when the POST body is empty or blank before the rate-limiter block can increment the counter. Currently an empty body propagates through `extractDsnFromEnvelope` and causes an unhandled exception at the Spring framework level, producing a 500.

**Contract**: Insert at the top of `tunnel()`, before the rate-limiter block:
```java
if (body == null || body.trim().isEmpty()) {
    log.warn("Invalid Sentry envelope: empty body");
    return ResponseEntity.badRequest().build();
}
```

#### 3. Wire reset call into test `@BeforeEach`

**File**: `src/test/java/com/example/finance_hq/web/SentryTunnelControllerIntegrationTest.java`

**Intent**: Reset the rate-limiter state before each test so all four tests start from a clean window regardless of JUnit execution order.

**Contract**: Add `@Autowired private SentryTunnelController sentryTunnelController;` as a field after the existing `@Autowired WebApplicationContext` field (line 28). At the end of `setup()`, call `sentryTunnelController.resetRateLimiter();`.

### Success Criteria

#### Automated Verification

- `./mvnw test` passes with 84/84 (was 80/84 before this fix)
- `testTunnelWithEmptyBody_Returns400` returns 400
- `testTunnelWithMissingDsn_Returns400` returns 400
- `testTunnelWithUnknownDsn_Returns400` returns 400
- `testTunnelRateLimiting_Returns429OnExceed` still returns 429 on the 101st request

**Implementation Note**: After this fix passes automated verification, items 3.2 and 3.3 in the Progress section can be checked.

---

## Phase 4: Frontend (Angular) SDK Integration

### Overview

Install `@sentry/angular`, initialise Sentry in `main.ts` before Angular bootstraps (guarded by empty-DSN check for dev), replace Angular's `ErrorHandler` with Sentry's implementation, and wire `captureConsoleIntegration` to forward `console.warn()` and `console.error()` calls as Sentry events.

### Changes Required

#### 1. Install `@sentry/angular`

**File**: `src/main/frontend/package.json`

**Intent**: Add the Sentry Angular SDK as a runtime production dependency.

**Contract**: Add `"@sentry/angular": "^10.52.0"` to `dependencies` (not `devDependencies` — it is bundled into the production app). Run `npm install` after editing.

#### 2. Add Sentry DSN to the production environment

**File**: `src/main/frontend/src/environments/environment.ts`

**Intent**: Bake the frontend DSN and a release placeholder into the production bundle. This file IS the production environment (the dev build configuration replaces it with `environment.development.ts`).

**Contract**: Add two fields:
- `sentryDsn: 'https://<key>@sentry.io/<projectId>'` — replace with the frontend DSN from Phase 1
- `sentryRelease: ''` — left empty here; GitHub Actions will patch this to the git SHA at build time in Phase 5

#### 3. Disable Sentry in the development environment

**File**: `src/main/frontend/src/environments/environment.development.ts`

**Intent**: Ensure local dev never sends events to Sentry.

**Contract**: Add `sentryDsn: ''` and `sentryRelease: ''` fields. The guard in `main.ts` (`if (environment.sentryDsn)`) skips `Sentry.init()` when the DSN is empty, so no further configuration is needed.

#### 4. Initialise Sentry in main.ts

**File**: `src/main/frontend/src/main.ts`

**Intent**: Initialise Sentry before Angular's `bootstrapApplication()` call so that bootstrapping errors are captured. The guard on `sentryDsn` ensures this is a no-op in dev.

**Contract**:
- Import `* as Sentry from '@sentry/angular'` and `captureConsoleIntegration` from `@sentry/browser`
- Import `environment` from `./environments/environment`
- Before `bootstrapApplication(App, appConfig)`: call `Sentry.init({...})` guarded by `if (environment.sentryDsn)`
- Sentry.init config: `dsn: environment.sentryDsn`, `tunnel: '/sentry-tunnel'`, `environment: 'production'`, `release: environment.sentryRelease || undefined`, `tracesSampleRate: 0`, `integrations: [captureConsoleIntegration({ levels: ['warn', 'error'] })]`
- `beforeSend(event)`: regex-replace email patterns (`[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}` → `[scrubbed]`) in `event.message`, then return the event

#### 5. Register SentryErrorHandler

**File**: `src/main/frontend/src/app/app.config.ts`

**Intent**: Replace Angular's default `ErrorHandler` with Sentry's so uncaught Angular errors (component throws, unhandled observable errors) reach Sentry.

**Contract**: Add `{ provide: ErrorHandler, useClass: SentryErrorHandler }` from `@sentry/angular` to the `providers` array. Keep the existing `provideBrowserGlobalErrorListeners()` — it handles unhandled Promise rejections at the platform level and complements `SentryErrorHandler` (the two are not redundant).

### Success Criteria

#### Automated Verification

- `npm run build` completes without errors from `src/main/frontend/`
- `npm test` passes

#### Manual Verification

- Deploy to Railway; in browser DevTools → Network tab, confirm `POST /sentry-tunnel` requests fire on page errors (not direct `sentry.io` requests)
- Deliberately trigger an unhandled error in the Angular app (e.g. throw from a component); verify it appears in `finance-hq-frontend` Sentry within 60 seconds
- In browser DevTools console: `console.warn('sentry-test-warning')` → verify a warning-level event appears in Sentry
- Confirm user email is absent from Sentry event payloads
- Run `ng serve` locally; verify zero network requests to `/sentry-tunnel` and zero Sentry events (DSN is empty in `environment.development.ts`)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 5: Source Maps + CI/CD

### Overview

Enable hidden source maps in the Angular production build, inject the git SHA as the Sentry release at build time, and upload source maps to Sentry via a new GitHub Actions job so that Angular stack traces in Sentry show TypeScript source lines.

### Changes Required

#### 1. Enable hidden source maps in the production build

**File**: `src/main/frontend/angular.json`

**Intent**: Generate `.js.map` files during `ng build --configuration production` without embedding `//# sourceMappingURL=` comments in the bundle. Browsers never request the maps; sentry-cli uploads them directly.

**Contract**: In the `"production"` configuration object, add:
```json
"sourceMap": {
  "scripts": true,
  "hidden": true
}
```

#### 2. Inject git SHA as Sentry release at build time

**File**: `.github/workflows/deploy.yml` (the build step in the new job)

**Intent**: Stamp the Angular bundle with the git SHA so `Sentry.init({ release })` and `sentry-cli sourcemaps upload --release` use the same value, enabling Sentry to match events to the correct uploaded source maps.

**Contract**: In the GitHub Actions `upload-sourcemaps` job (see change 3 below), before running `npm run build`, patch `environment.ts` to set `sentryRelease` to `${{ github.sha }}`. The simplest approach is a `sed` replacement on the `sentryRelease: ''` line. The patched file is used only within the CI build — it is never committed.

#### 3. Add source map upload job to GitHub Actions

**File**: `.github/workflows/deploy.yml`

**Intent**: After the health check confirms Railway has deployed successfully, build the Angular app in CI, inject the release SHA, and upload source maps to Sentry. The job runs after `deploy` (the existing health-check job) so both jobs share the same `github.sha`.

**Contract**: Add a new job `upload-sourcemaps` that `needs: deploy`:
1. `actions/checkout@v4`
2. `actions/setup-node@v4` with `node-version: '22'`
3. `cd src/main/frontend && npm ci`
4. Patch `sentryRelease` in `src/main/frontend/src/environments/environment.ts` to `${{ github.sha }}` using `sed`
5. `npm run build` (outputs to `target/classes/static/` at project root)
6. `npx @sentry/cli sourcemaps inject target/classes/static` — injects Sentry metadata into map files
7. `npx @sentry/cli sourcemaps upload --org ${{ secrets.SENTRY_ORG }} --project finance-hq-frontend --release ${{ github.sha }} target/classes/static`

Env vars needed in the job: `SENTRY_AUTH_TOKEN: ${{ secrets.SENTRY_AUTH_TOKEN }}`.

#### 4. Add GitHub Actions secrets

**Intent**: Authenticate sentry-cli for the upload step.

**Contract**: In GitHub repository → Settings → Secrets and variables → Actions, add:
- `SENTRY_AUTH_TOKEN` — generate from Sentry: Settings → Developer Settings → Internal Integrations → New Integration, grant "Release" + "Source Maps" write permissions, copy the token
- `SENTRY_ORG` — your Sentry organisation slug (visible in the Sentry dashboard URL: `sentry.io/organizations/<slug>/`)

### Success Criteria

#### Automated Verification

- `npm run build` generates `.js.map` files in `target/classes/static/`
- GitHub Actions `upload-sourcemaps` job exits 0 on a push to master

#### Manual Verification

- In Sentry `finance-hq-frontend` project → Releases, confirm a release named with the full git SHA appears after the next push to master
- Trigger an Angular error in production; open the Sentry event and verify the stack trace shows `.ts` file paths and line numbers (not minified bundle references)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests

- `SentryBeforeSendCallback` — event with email in message → message contains `[scrubbed]` instead; event without email → passes through unchanged
- `SentryTunnelController` — unknown DSN → 400; 101st request in window → 429; valid known DSN → proxies request and returns Sentry's status code

### Integration Tests

- `POST /sentry-tunnel` with a well-formed envelope and the known frontend DSN — reaches a mock Sentry server (WireMock) and returns 200
- `POST /sentry-tunnel` with an unknown DSN — 400, no outbound request made

### Manual Testing Steps

1. Deploy to Railway after Phase 2; from a browser: load the app, check Network tab confirms no direct `sentry.io` calls
2. Trigger a deliberate Angular throw in the browser; confirm event appears in `finance-hq-frontend` Sentry within 60 seconds
3. From browser DevTools: `console.warn('sentry-check')` → confirm warning event in Sentry
4. From a controller test endpoint or Spring shell: emit `log.warn("backend-sentry-check")` → confirm event in `finance-hq-backend` Sentry
5. After Phase 5 deploys: trigger an Angular error in production; confirm Sentry stack trace shows TypeScript source lines

## Performance Considerations

`tracesSampleRate=0.0` on both SDKs — no distributed tracing overhead. The tunnel endpoint is a thin HTTP proxy (synchronous `RestClient` call) with negligible added latency per Sentry event. Sentry event queuing is async and non-blocking in both SDKs.

## Migration Notes

No existing monitoring to migrate. To disable Sentry later: remove `SENTRY_DSN` from Railway → the Spring Boot SDK disables itself on next deploy. For the frontend: empty `sentryDsn` in `environment.ts` → `Sentry.init()` is skipped.

## References

- Related change: `context/changes/sentry-monitoring/change.md`
- Sentry Spring Boot 4 docs: `https://docs.sentry.io/platforms/java/guides/spring-boot/`
- Sentry Angular docs: `https://docs.sentry.io/platforms/javascript/guides/angular/`
- Sentry tunnel docs: `https://docs.sentry.io/platforms/javascript/troubleshooting/#using-the-tunnel-option`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Sentry Project Setup

#### Manual

- [x] 1.1 Both `finance-hq-backend` and `finance-hq-frontend` projects exist in Sentry dashboard
- [x] 1.2 Both DSNs are copied and stored for Phases 2 and 4
- [x] 1.3 Alert rules are active on both projects and show "New Issue → Email" action

### Phase 2: Backend (Spring Boot) SDK Integration

#### Automated

- [x] 2.1 `./mvnw test` passes — Sentry SDK must not interfere with Testcontainers integration test context — 67ce66c
- [x] 2.2 App starts locally with `./mvnw spring-boot:run --spring.profiles.active=local` without Sentry-related errors — 67ce66c

#### Manual

- [x] 2.3 In Railway logs confirm `SentryOptions` initialises after deploy
- [x] 2.4 Trigger a deliberate `log.warn` call; verify event appears in `finance-hq-backend` Sentry within 60 seconds
- [x] 2.5 Confirm no `@` email addresses appear in the Sentry event detail

### Phase 3: Sentry Tunnel Endpoint

#### Automated

- [x] 3.1 `./mvnw test` passes (80/84 tests pass; tunnel test isolation fixed in Phase 3 Fix) — 818eb0e
- [x] 3.2 Integration test: unknown DSN → 400 — 2556c45
- [x] 3.3 Integration test: 101st rapid request → 429 — 2556c45

#### Manual

- [x] 3.4 Valid Sentry envelope forwarded through tunnel reaches `finance-hq-frontend` Sentry
- [x] 3.5 Browser Network tab shows no direct requests to `sentry.io`

### Phase 3 Fix: Test Isolation

#### Automated

- [x] 3F.1 `./mvnw test` passes 84/84 — all four `SentryTunnelControllerIntegrationTest` cases green — 2556c45

### Phase 4: Frontend (Angular) SDK Integration

#### Automated

- [x] 4.1 `npm run build` completes without errors — 67ce66c
- [x] 4.2 `npm test` passes — 67ce66c

#### Manual

- [x] 4.3 `POST /sentry-tunnel` fires in Network tab on Angular errors (no direct `sentry.io` calls)
- [x] 4.4 Unhandled Angular error appears in `finance-hq-frontend` Sentry within 60 seconds
- [x] 4.5 `console.warn('sentry-test-warning')` produces a warning-level Sentry event
- [x] 4.6 User email is absent from Sentry event payloads
- [x] 4.7 `ng serve` locally generates zero Sentry network requests

### Phase 5: Source Maps + CI/CD

#### Automated

- [ ] 5.1 `npm run build` generates `.js.map` files in `target/classes/static/`
- [ ] 5.2 GitHub Actions `upload-sourcemaps` job exits 0 on push to master

#### Manual

- [ ] 5.3 Release named with full git SHA appears in Sentry `finance-hq-frontend` Releases after next push
- [ ] 5.4 Production Angular error stack trace shows `.ts` file paths and line numbers
