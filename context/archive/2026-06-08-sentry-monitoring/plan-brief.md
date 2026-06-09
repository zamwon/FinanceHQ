# Sentry Error Monitoring — Plan Brief

> Full plan: `context/changes/sentry-monitoring/plan.md`

## What & Why

Add Sentry error and warning tracking to both the Spring Boot backend and Angular frontend so that production failures surface immediately rather than requiring manual log inspection. The app currently has zero observability beyond an Actuator health endpoint — notification job failures and Angular exceptions are silent unless the user actively checks Railway logs.

## Starting Point

Both layers have partial error handling (a `@RestControllerAdvice` global handler on the backend, component-level error state on the frontend) but nothing that reports to an external system. The existing SecurityConfig enforces `default-src 'self'` CSP, which blocks direct browser connections to `sentry.io`.

## Desired End State

After deployment: any Spring Boot `log.warn()` / `log.error()` and any unhandled Angular exception or `console.warn()` / `console.error()` is captured in Sentry within seconds, with an email alert to blazej.karnecki@gmail.com on the first occurrence. Angular stack traces show TypeScript source lines (not minified output). User email addresses never leave the app in Sentry payloads.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Scope | Both backend + frontend | Full-stack visibility needed — notification failures and Angular errors are equally production-critical | Plan |
| Sentry projects | Two separate (`finance-hq-backend`, `finance-hq-frontend`) | Keeps Java stack traces and JS errors in separate inboxes with independent alert rules | Plan |
| Capture level | Errors + warnings, no performance traces | `minimum-event-level=WARN` gives broader signal; `tracesSampleRate=0.0` keeps the free plan quota intact | Plan |
| PII handling | Scrub email via `beforeSend` regex | User email appears in notification failure log messages; must not reach Sentry's servers | Plan |
| Environments | Production (Railway) only | Empty DSN → SDK self-disables; local dev stays clean | Plan |
| CSP approach | Backend tunnel endpoint (`POST /sentry-tunnel`) | Proxying through our own domain requires zero CSP relaxation | Plan |
| Source maps | Upload via sentry-cli in GitHub Actions, hidden maps | TypeScript-level stack traces; browser never loads the maps | Plan |
| Alerting | Email to blazej.karnecki@gmail.com, first-occurrence only | Immediate notification without inbox flooding | Plan |

## Scope

**In scope:**
- `io.sentry:sentry-spring-boot-4-starter` wired to the Spring Boot 4 backend
- `@sentry/angular` wired to the Angular 21 frontend
- `POST /sentry-tunnel` Spring Boot endpoint (DSN allow-list + in-memory rate limiter)
- `SentryBeforeSendCallback` email scrubber (backend)
- `beforeSend` + `captureConsoleIntegration` (frontend)
- Angular `SentryErrorHandler` in `app.config.ts`
- Hidden source maps in production build + sentry-cli upload in GitHub Actions

**Out of scope:**
- Performance tracing / distributed traces
- Sentry session replay
- Local dev reporting
- Backend release versioning (frontend source maps cover the symbolication use case)
- CSP changes

## Architecture / Approach

```
Browser (Angular)
  └── Sentry SDK (tunnel: '/sentry-tunnel')
        └── POST /sentry-tunnel (Spring Boot controller)
              ├── Validate DSN against allow-list
              ├── Rate-limit (100 req/min)
              └── Forward envelope → https://sentry.io/api/{projectId}/envelope/

Spring Boot JVM
  └── sentry-spring-boot-4-starter
        ├── Captures log.warn / log.error via SLF4J integration
        ├── Captures unhandled exceptions via @RestControllerAdvice hook
        └── SentryBeforeSendCallback scrubs email before sending
              └── Direct to → https://sentry.io/api/{backendProjectId}/envelope/
```

GitHub Actions: post-deploy job runs `npm run build` (with git SHA patched into `sentryRelease`), then `sentry-cli sourcemaps inject + upload`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Sentry project setup | Two projects, two DSNs, alert rules | Manual — nothing can proceed without DSNs |
| 2. Backend SDK | `log.warn`/`log.error` + exceptions → Sentry; email scrubbing | Spring Boot 4 artifact name confusion (`-4-` infix critical) |
| 3. Tunnel endpoint | Browser events proxied without CSP relaxation | Allow-list must be set to the real frontend DSN before deploy |
| 4. Frontend SDK | Angular exceptions + console warnings → Sentry | Empty DSN guard must work correctly; `SentryErrorHandler` must not conflict with `provideBrowserGlobalErrorListeners()` |
| 5. Source maps + CI | TypeScript-level stack traces in Sentry | GitHub Actions build output path must match `target/classes/static/` (non-standard for Angular) |

**Prerequisites:** Phase 1 DSNs must exist before coding Phases 2–4. Phases 2–4 can be developed in parallel. Phase 5 requires Phase 4's `sentryRelease` field to exist.

**Estimated effort:** ~2–3 sessions across 5 phases.

## Open Risks & Assumptions

- `io.sentry:sentry-spring-boot-4-starter` version 8.26.0 was the latest confirmed at plan time — verify at implementation time
- Angular esbuild Application Builder `define` option is used for release injection; if it behaves differently at build time, the `sed` patch approach is the fallback
- GitHub Actions `upload-sourcemaps` job runs the Angular build a second time (Railway builds via Dockerfile independently) — this doubles build time in CI but is the only way to capture source maps before they are discarded by Railway's build

## Success Criteria (Summary)

- A production `log.warn()` or unhandled Angular exception appears in Sentry within 60 seconds of occurrence
- Email alert arrives at blazej.karnecki@gmail.com on first new issue
- Angular Sentry stack traces show TypeScript file names and line numbers
