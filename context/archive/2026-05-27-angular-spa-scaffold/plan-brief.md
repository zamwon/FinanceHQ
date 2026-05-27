# Angular SPA Scaffold (F-03) — Plan Brief

> Full plan: `context/changes/angular-spa-scaffold/plan.md`

## What & Why

Scaffold an Angular SPA at `src/main/frontend/` with routing, an auth guard, and a JWT-aware HTTP interceptor, built into the Spring Boot JAR by Maven. F-03 is the parallel-track frontend foundation in the roadmap — it unblocks the user-visible slices (S-01 register/login, S-02 obligation list, S-03 edit/delete) by delivering a ready harness on top of the already-shipped F-02 auth backend.

## Starting Point

No frontend exists today — `src/main/frontend/` is missing and `src/main/resources/static/` is empty. The backend is auth-complete: `POST /auth/{register,login,refresh,logout}` returns `{ accessToken, refreshToken, tokenType: "Bearer", expiresIn }` and `JwtAuthenticationFilter` validates `Authorization: Bearer` on protected routes. CORS is pre-wired for `http://localhost:4200`.

## Desired End State

`./mvnw clean package` produces a single executable JAR that serves the SPA at `/`. Three stub screens (`/login`, `/register`, `/dashboard`) render under a Material toolbar. An auth guard redirects unauthenticated users away from `/dashboard`; an HTTP interceptor attaches Bearer tokens and refreshes once on 401. Deep-link reloads (e.g., `http://localhost:8080/dashboard`) return `index.html` so Angular handles routing. `ng serve` proxies `/auth/**` to the backend for hot-reload dev.

## Key Decisions Made

| Decision                          | Choice                                                    | Why (1 sentence)                                                                                  | Source |
| --------------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------- | ------ |
| F-03 scope vs S-01                | Stubs + full auth plumbing; no working forms              | Roadmap explicitly says "stub screens; wire auth guard" — S-01 owns the user-visible login flow.  | Plan   |
| Build integration                 | `frontend-maven-plugin` (Maven-driven)                    | Single `./mvnw clean package` builds both; existing Dockerfile keeps working unchanged.           | Plan   |
| Token storage                     | `localStorage` for both access + refresh                  | Matches the existing Bearer-header backend contract with zero refactor; XSS hardening is v1.1.    | Plan   |
| SPA deep-link fallback            | `WebMvcConfigurer` view-controller forward to `/index.html` | Explicit, testable Spring pattern; one config class; dot-rule excludes static assets cleanly.    | Plan   |
| 401 handling                      | Auto-refresh once, then redirect; queue concurrent calls  | Matches what the refresh token was built for; one place owns the retry contract.                  | Plan   |
| Styling toolkit                   | Angular Material                                          | PRD needs forms, list views, and a confirmation dialog — all batteries-included in Material.      | Plan   |
| Test runner                       | Vitest (Angular CLI default in v21)                       | New `ng new` projects ship Vitest out of the box — no Karma migration tax.                         | Plan   |

## Scope

**In scope:**
- Angular workspace under `src/main/frontend/` with routing, Material, and standalone components
- `frontend-maven-plugin` wiring; Angular `outputPath` flattened to `target/classes/static/`
- Stub components for `/login`, `/register`, `/dashboard`, `/`, `**`
- `TokenStorageService`, `AuthService`, `authInterceptor` (with auto-refresh queue), `authGuard`
- Spring `WebMvcConfigurer` for SPA deep-link fallback
- One Vitest spec (guard) + one MockMvc spec (fallback)
- Dev-server proxy config for `/auth/**`

**Out of scope:**
- Working login/register forms — S-01
- Any obligation feature code — S-02 / S-03
- SSR / hydration / PWA / service worker
- httpOnly-cookie auth refactor (XSS hardening) — v1.1
- RFC 7807 client-side error parsing — queued behind backend migration
- CI YAML changes — existing `./mvnw clean package` Just Works
- CDN fronting Railway — parked

## Architecture / Approach

```
src/main/frontend/                       <-- Angular workspace (standalone components)
├── angular.json                         outputPath: ../../../target/classes/static (flattened)
├── proxy.conf.json                      /auth/** → :8080 in dev
└── src/app/
    ├── app.component.ts                 Material toolbar + <router-outlet>
    ├── app.config.ts                    provideRouter + provideHttpClient([authInterceptor])
    ├── app.routes.ts                    lazy routes; [authGuard] on /dashboard
    ├── core/auth/                       TokenStorageService, AuthService, authInterceptor, authGuard
    └── features/                        login, register, dashboard, not-found (stub cards)

pom.xml: frontend-maven-plugin (Node 22, npm 11) — install in generate-resources, build in prepare-package
src/main/java/.../web/SpaForwardingConfig.java: forward /{path:[^\.]*} → /index.html
```

`./mvnw clean package` → Maven downloads Node into `target/`, runs `npm ci`, runs `ng build` with prod config, outputs to `target/classes/static/`, packages JAR. `java -jar` boots Spring Boot which serves SPA from `classpath:/static/`. Deep links forward to `index.html`; static assets bypass the forwarder via the dot-rule; backend routes (`/auth/**`, `/actuator/**`) win over the forwarder by controller precedence.

## Phases at a Glance

| Phase                                            | What it delivers                                             | Key risk                                                                                             |
| ------------------------------------------------ | ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| 1. Angular workspace + Maven build integration   | `ng new` scaffold + `frontend-maven-plugin` wired; JAR includes SPA | Build-tool integration regressions if Node/npm versions drift; first cold build adds ~30s            |
| 2. SPA shell + routing + stub components         | Toolbar, route table, four placeholder feature components    | Initial bundle exceeds 800 KB Angular budget if Material imports aren't tree-shakeable               |
| 3. Auth plumbing (interceptor, refresh, guard)   | TokenStorageService, AuthService, interceptor + guard wired  | Concurrent-request refresh storm if the BehaviorSubject queue is implemented wrong                   |
| 4. Spring SPA fallback + production smoke        | `WebMvcConfigurer` deep-link forward; two smoke tests        | Fallback regex matching static assets (missing dot-rule) silently breaks production asset serving    |

**Prerequisites:** F-01 (done), F-02 (done — auth backend shipped). Node ≥ 22 reachable for download by Maven; npm ≥ 11. No additional infra.
**Estimated effort:** ~1–2 focused sessions across the 4 phases (solo dev, after-hours pace).

## Open Risks & Assumptions

- Angular CLI version drift: pinning `frontend-maven-plugin` to Node 22.20 LTS — if Angular 22 raises the floor mid-sprint, the version bump is a one-line `pom.xml` change.
- `frontend-maven-plugin` cold Node download (~50 MB) inflates every fresh Docker build on Railway until a multi-stage Docker frontend cache is added (deferred).
- `localStorage` tokens carry known XSS risk; explicitly accepted for MVP single-user scope and flagged for v1.1 hardening.

## Success Criteria (Summary)

- A single `./mvnw clean package` produces a self-contained JAR that, when launched, serves the Angular SPA at `http://localhost:8080/`.
- Hard-reloading `/dashboard` works (SPA fallback) and triggers the auth guard's redirect when no token is present.
- HTTP interceptor attaches `Authorization: Bearer <token>` to protected requests; refreshes once on 401 with concurrent-request safety; redirects to `/login` on refresh failure.
