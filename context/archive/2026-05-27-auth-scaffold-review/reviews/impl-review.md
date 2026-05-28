<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: F-02 Auth Scaffold

- **Plan**: context/archive/2026-05-25-f-02-auth-scaffold/plan.md
- **Scope**: All Phases (1–6 of 6)
- **Date**: 2026-05-27
- **Verdict**: PASS
- **Findings**: 0 critical  5 warnings (all resolved)  5 observations (all resolved/deferred)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — register() TOCTOU race produces uncontrolled 500

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/auth/AuthService.java:44
- **Detail**: register() calls existsByEmail() then save() as two separate DB round-trips with no transaction boundary. Under concurrent requests with the same email both can pass the guard — the DB unique constraint catches it but throws DataIntegrityViolationException, which GlobalExceptionHandler does not handle → uncontrolled 500 instead of a clean 409.
- **Fix A ⭐ Recommended**: Remove existsByEmail() pre-check; add @ExceptionHandler(DataIntegrityViolationException.class) → 409 in GlobalExceptionHandler.
  - Strength: Eliminates the race and the extra round-trip; DB constraint is the correct gate.
  - Tradeoff: Error path relies on exception translation rather than explicit guard.
  - Confidence: HIGH — standard Spring pattern for unique-constraint violations.
  - Blind spot: DataIntegrityViolationException can also come from other constraints — handler should be specific.
- **Fix B**: Add @Transactional to register() + keep the pre-check.
  - Strength: Keeps explicit guard logic readable.
  - Tradeoff: Does not fully close the gap without Fix A's handler.
  - Confidence: MEDIUM.
  - Blind spot: None significant.
- **Decision**: RESOLVED — Fix A applied. `existsByEmail()` removed from `register()`; `DataIntegrityViolationException` handler added to `GlobalExceptionHandler`.

### F2 — GlobalExceptionHandler has no catch-all handler

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java
- **Detail**: Any exception not explicitly handled (e.g. DataIntegrityViolationException, unexpected RuntimeException) falls through to Spring's default error handling, which may expose stack traces or return inconsistent response shapes.
- **Fix**: Add @ExceptionHandler(Exception.class) fallback returning 500 Map.of("error", "Internal server error"), plus a specific DataIntegrityViolationException handler returning 409 (ties into F1).
- **Decision**: RESOLVED — Both handlers added.

### F3 — SecurityConfig permits all GET /auth/** unauthenticated

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/security/SecurityConfig.java:41
- **Detail**: .requestMatchers(HttpMethod.GET, "/auth/**").permitAll() was added so controllers return 405 instead of 401 for non-existent GET handlers. This is overly broad: any future GET endpoint under /auth/ would be silently unprotected.
- **Fix A ⭐ Recommended**: Remove the rule entirely — a 401 for unauthenticated GET on /auth/** is acceptable.
  - Strength: Eliminates the latent risk; no test covers this rule.
  - Tradeoff: curl -X GET /auth/login returns 401 instead of 405.
  - Confidence: HIGH — no test covers this rule; removing it won't break the suite.
  - Blind spot: None significant.
- **Fix B**: Enumerate explicit paths: .requestMatchers(GET, "/auth/register", "/auth/login", etc.).permitAll().
  - Strength: Preserves 405 on known paths only.
  - Tradeoff: Must stay in sync with AuthController routes.
  - Confidence: MEDIUM.
  - Blind spot: None significant.
- **Decision**: RESOLVED — Fix A applied. Broad `GET /auth/**` rule removed; only explicit POST paths are permitted.

### F4 — JwtService.extractEmail() unchecked throw + double-parse in filter

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/auth/JwtService.java:36
- **Detail**: extractEmail() calls parseSignedClaims() without try-catch. The filter calls isTokenValid() first then extractEmail() — parsing twice. A JwtException from extractEmail() is not handled by GlobalExceptionHandler and surfaces as a 500.
- **Fix**: Wrap extractEmail() in try-catch returning null on JwtException; null-check result in filter (treat null as unauthenticated).
- **Decision**: RESOLVED — try-catch added to `extractEmail()`; filter null-checks the result.

### F5 — jwt.secret has no local fallback default

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/application.properties:25
- **Detail**: application.properties has jwt.secret=${JWT_SECRET} with no fallback. Per lessons.md "fallback defaults" rule, all Railway-injected env vars should use ${VAR:default} syntax. Without a fallback the app fails to start when JWT_SECRET is not set locally.
- **Fix**: Change to jwt.secret=${JWT_SECRET:dGVzdHNlY3JldHRlc3RzZWNyZXR0ZXN0c2VjcmV0dGVzdA==} (same test secret as application-test.properties — Railway overrides in prod).
- **Decision**: NO ACTION — project uses `--spring.profiles.active=local` for local dev (per lessons.md); `JWT_SECRET` is set there. Railway injects in prod.

### F6 — RefreshTokenService.create() missing @Transactional

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/auth/RefreshTokenService.java:27
- **Detail**: create() has no @Transactional. When called from login() (no transaction) a save failure leaves caller with an unhandled exception. When called from rotate() (which is @Transactional) it joins the outer transaction correctly.
- **Fix**: Add @Transactional to create().
- **Decision**: RESOLVED — `@Transactional` added to `create()`.

### F7 — User implements UserDetails directly (documented tech debt)

- **Severity**: OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architecture
- **Location**: src/main/java/com/example/finance_hq/user/User.java:13
- **Detail**: User entity implements UserDetails directly (documented in lessons.md). Risk: getPassword() exposes passwordHash; no role separation possible without schema changes.
- **Fix**: No action in v0.1. Pre-S-01 refactor: introduce UserPrincipal adapter; add @JsonIgnore to getPassword() as a minimal defensive measure now.
- **Decision**: RESOLVED — `@JsonIgnore` added to `getPassword()`. Full UserPrincipal refactor deferred to pre-S-01.

### F8 — No DB index on refresh_tokens.token (non-issue)

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/user/RefreshToken.java:15
- **Detail**: No explicit @Index in the entity. However, the UNIQUE constraint on token implies a B-tree index in PostgreSQL — this is actually fine.
- **Fix**: No action needed. UNIQUE constraint IS the index in PostgreSQL.
- **Decision**: NO ACTION NEEDED.

### F9 — logout endpoint is unauthenticated

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/auth/AuthController.java:43
- **Detail**: POST /auth/logout is in the permitAll list. An attacker who intercepts a refresh token can revoke it before the legitimate user, silently logging them out. Acceptable MVP risk for a single-user tool.
- **Fix**: No action in v0.1. Post-MVP: require valid Bearer token on logout and verify refresh token ownership.
- **Decision**: NO ACTION NEEDED — acceptable MVP risk; deferred to post-MVP.

### F10 — AuthControllerIntegrationTest uses MOCK env instead of RANDOM_PORT

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/test/java/com/example/finance_hq/auth/AuthControllerIntegrationTest.java
- **Detail**: Plan specified RANDOM_PORT + TestRestTemplate. Implementation uses MOCK env + MockMvc. Functionally equivalent; MockMvc with springSecurity() applied matches the pattern validated in SpaForwardingConfigTest. Justified deviation.
- **Fix**: No action needed.
- **Decision**: NO ACTION NEEDED — justified deviation; MockMvc with springSecurity() is functionally equivalent.
