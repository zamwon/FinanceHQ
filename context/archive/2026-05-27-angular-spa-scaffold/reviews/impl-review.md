<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Angular SPA Scaffold (F-03)

- **Plan**: context/changes/angular-spa-scaffold/plan.md
- **Scope**: All Phases (1–4 of 4)
- **Date**: 2026-05-27
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical  4 warnings  3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

## Findings

### F1 — signOut() swallows errors, leaving user in broken state

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/frontend/src/app/app.ts:19
- **Detail**: signOut() subscribes only to `complete`, not `error`. When POST /auth/logout fails (network error, 5xx), the observable errors — `finalize` in AuthService correctly clears localStorage, but `router.navigate(['/login'])` lives only in `complete` so it never fires. User ends up token-less but still on the current page.
- **Fix**: Add `error: () => this.router.navigate(['/login'])` alongside `complete` in the subscribe options.
  - Strength: One-line change; makes error path symmetric with success path.
  - Tradeoff: None.
  - Confidence: HIGH — finalize already clears state; only nav is missing.
  - Blind spot: None significant.
- **Decision**: FIXED — added `error: () => this.router.navigate(['/login'])` to subscribe options.

### F2 — SecurityConfig.java changed outside plan scope

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Scope Discipline / Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/security/SecurityConfig.java
- **Detail**: SecurityConfig was substantially updated outside plan scope. Permit rules added for SPA shell routes and a dot-based lambda matcher added to permit static assets. The dot-based matcher (`filename.contains(".")`) is fragile: any future API endpoint with a dot in its last path segment would be served without auth. The plan's "What We're NOT Doing" says no new API endpoints — but this assumption won't hold past S-01.
- **Fix A ⭐ Recommended**: Document as a plan addendum now; replace dot-based matcher with `PathRequest.toStaticResources().atCommonLocations()` before S-02.
  - Strength: Closes the fragile matcher before any API routes are added.
  - Tradeoff: Minor follow-up edit before S-02 begins.
  - Confidence: HIGH — Spring's static resources matcher is idiomatic and safe.
  - Blind spot: None significant.
- **Fix B**: Accept as-is and add a lesson about API paths with dots.
  - Strength: No code change now.
  - Tradeoff: Fragile security rule lives in production until someone catches it.
  - Confidence: LOW — easy to miss when adding S-02 endpoints.
  - Blind spot: Unknown if S-02 or later roadmap items add dot-segment paths.
- **Decision**: FIXED via Fix A — replaced dot-based lambda matcher with `PathRequest.toStaticResources().atCommonLocations()`; added CSP header.

### F3 — Interceptor BehaviorSubject retains stale token between refresh cycles

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/frontend/src/app/core/auth/auth.interceptor.ts:10
- **Detail**: `refresh$` is a `BehaviorSubject<string | null>` that retains its last emitted value. After a successful refresh it holds the new token but is never reset to null. BehaviorSubject replays its last value to new subscribers — in the JS single-threaded model today this is not exploitable, but the code is fragile by design. A plain `Subject<string>` (no replay semantics) makes the intent unambiguous and eliminates the class of risk.
- **Fix**: Replace `new BehaviorSubject<string | null>(null)` with `new Subject<string>()`, remove `| null` from the type, and remove the `filter(t => !!t)` pipe on line 43 (Subject never emits until explicit next() call).
  - Strength: Standard pattern for serialized-refresh; no stale-value risk by construction.
  - Tradeoff: Two-line change.
  - Confidence: HIGH — canonical RxJS pattern for this use case.
  - Blind spot: None significant.
- **Decision**: FIXED — replaced BehaviorSubject with Subject, removed filter pipe and null reset.

### F4 — `npm test -- --run` fails; progress 4.2 may be rubber-stamped

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/angular-spa-scaffold/plan.md (Phase 4 automated criteria / Progress 4.2)
- **Detail**: Progress 4.2 states `npm test -- --run` passes. Verified: `ng test` rejects `--run` with "Error: Unknown argument: run". `npm test` (runs `ng test --watch=false`) passes cleanly — 4 tests in 2 files. The `--run` flag is a raw Vitest flag; `@angular/build:unit-test` wraps Vitest and does not forward unknown arguments. Checkbox 4.2 was checked with dd63577 but the command would have failed then too.
- **Fix**: Update the plan's success criteria (Phases 1.3, 2.3, 3.3, 4.2) to use `npm test` (no `-- --run`).
  - Strength: Matches how Angular test runner actually works; reproduced locally.
  - Tradeoff: None.
  - Confidence: HIGH.
  - Blind spot: None.
- **Decision**: FIXED — updated plan success criteria in all phases to use `npm test`.

### F5 — Phase 2/3 boundary collapsed: auth code committed in Phase 2

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/frontend/src/app/app.routes.ts:16, src/main/frontend/src/app/app.config.ts
- **Detail**: The plan staged authGuard wiring and interceptor registration as Phase 3 work. In the diff, these were already in place at commit d193780 (Phase 2). The implementation is functionally correct — the forward-drift was convenient, no issues resulted — but makes the phase boundaries in the progress log misleading.
- **Fix**: No code change needed. Acknowledge as a process note.
- **Decision**: SKIPPED — process note only; no code change.

### F6 — SpaForwardingConfigTest uses manual MockMvc instead of @AutoConfigureMockMvc

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/test/java/com/example/finance_hq/web/SpaForwardingConfigTest.java:30
- **Detail**: Plan specified @AutoConfigureMockMvc. Actual test uses MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()) to manually build MockMvc. This is actually more correct — it ensures Spring Security's filter chain is applied so the SPA permit rules run. The three test cases match the plan exactly. Justified deviation.
- **Fix**: No action needed. This is better than the plan specified.
- **Decision**: SKIPPED — justified deviation; MockMvc with springSecurity() applied is more correct.

### F7 — localStorage XSS exposure (known accepted risk)

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/frontend/src/app/core/auth/token-storage.service.ts:9
- **Detail**: Access and refresh tokens in localStorage are readable by any JS on the page. Refresh token theft gives durable session access. The plan explicitly parked httpOnly-cookie hardening as a v1.1 dependency before production use.
- **Fix**: No action in v0.1. Adding a CSP header on Spring Boot responses would reduce XSS exposure before v1.1 ships.
- **Decision**: ACCEPTED-AS-RULE: localStorage XSS exposure — CSP header added as compensating control; lesson saved.
