<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: F-02 Auth Scaffold

- **Plan**: context/changes/f-02-auth-scaffold/plan.md
- **Scope**: All 6 phases
- **Date**: 2026-05-27
- **Verdict**: REJECTED (1 critical finding) → all critical/warning findings fixed; observations triaged
- **Findings**: 1 critical · 4 warnings · 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | FAIL (pre-fix) — all critical + warnings now resolved |
| Architecture | WARNING (User implements UserDetails — recorded as lesson) |
| Pattern Consistency | PASS |
| Success Criteria | PASS (22/22 tests pass; no javax deps) |

## Findings

### F1 — JWT signing-key default in application.properties is a usable HS256 secret

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff vs. lessons.md "use fallback defaults"
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/application.properties:25
- **Detail**: `jwt.secret=${JWT_SECRET:dGVzdHNlY3JldHRlc3RzZWNyZXR0ZXN0c2VjcmV0dGVzdA==}` decodes to a valid 32-byte HMAC key. If `JWT_SECRET` is unset in prod, the app silently boots with a public, source-controlled signing key — anyone reading the repo can mint valid access tokens.
- **Fix**: Removed the default — `jwt.secret=${JWT_SECRET}`. Test secret stays in `application-test.properties` only.
- **Decision**: FIXED

### F2 — Filter throws UsernameNotFoundException → uncaught 500

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/security/JwtAuthenticationFilter.java:45
- **Detail**: A valid JWT for a deleted user would cause `loadUserByUsername` to throw, bypass `@RestControllerAdvice`, and surface as a default 500.
- **Fix**: Wrapped the load+set-context block in try/catch on `UsernameNotFoundException`; fall through so the entry point produces 401.
- **Decision**: FIXED

### F3 — Refresh-token rotation has a race window

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — defeats rotation as a token-reuse signal
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/auth/RefreshTokenService.java:36-48
- **Detail**: Two concurrent refresh calls with the same token both passed presence+expiry, both deleted (idempotent), both created — yielding two valid refresh tokens.
- **Fix A ⭐**: Added `findByTokenForUpdate` with `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `RefreshTokenRepository`; `rotate` now uses it. Concurrent rotations serialize on the row.
- **Decision**: FIXED via Fix A

### F4 — Login timing leak enables email enumeration

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/auth/AuthService.java:48-53
- **Detail**: Unknown-email branch returned ~100–300ms faster than the wrong-password branch (no BCrypt cost), allowing email enumeration despite identical HTTP status.
- **Fix**: On unknown email, run `passwordEncoder.matches(req.password(), dummyHash)` against a precomputed BCrypt hash before throwing — equalizes timing.
- **Decision**: FIXED

### F5 — JWT parser uses zero clock skew tolerance

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/auth/JwtService.java
- **Detail**: Default skew of 0s causes hard rejections on small client/server drift around the expiry boundary.
- **Fix**: Added `.clockSkewSeconds(30)` to both parser builders. Bumped `JwtServiceTest.isTokenValid_falseForExpiredToken` to use −120s so it exceeds the new tolerance.
- **Decision**: FIXED

### F6 — Missing indexes on refresh_tokens(expires_at, user_id)

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Safety & Quality (Data Safety)
- **Location**: src/main/resources/db/migration/V2__create_refresh_tokens_table.sql
- **Detail**: `token UNIQUE` covers the hot lookup; `expires_at` and `user_id` have no index for future cleanup / FK deletes.
- **Fix**: Added `V3__add_refresh_tokens_indexes.sql` (V2 is already applied to prod — additive V3 avoids Flyway checksum repair).
- **Decision**: FIXED via new V3 migration

### F7 — User entity directly implements UserDetails

- **Severity**: 📝 OBSERVATION
- **Impact**: 🔎 MEDIUM
- **Dimension**: Architecture
- **Location**: src/main/java/com/example/finance_hq/user/User.java
- **Detail**: Mixes JPA entity with Spring Security's `UserDetails` contract — schema-couples future security flags (locked, enabled).
- **Fix**: Recorded as lesson in `context/foundation/lessons.md` ("JPA entity should not implement Spring Security UserDetails directly"). Code left as-is for MVP per user decision.
- **Decision**: ACCEPTED-AS-RULE — JPA entity ≠ UserDetails

### F8 — AuthControllerIntegrationTest uses MockMvc instead of RANDOM_PORT + TestRestTemplate

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Plan Adherence
- **Location**: src/test/java/com/example/finance_hq/auth/AuthControllerIntegrationTest.java
- **Detail**: Plan specified RANDOM_PORT + TestRestTemplate; impl uses MockMvc with `springSecurity()`. Functionally equivalent.
- **Decision**: SKIPPED — functionally equivalent, no value in reverting.

### F9 — No integration test for tampered/expired token vs. protected route

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Safety & Quality (Test Quality)
- **Location**: src/test/java/com/example/finance_hq/auth/AuthControllerIntegrationTest.java
- **Detail**: Existing tests covered no-token-401 and valid-token-not-401; the in-between (tampered bearer → 401) was untested.
- **Fix**: Added `protectedEndpoint_401_tamperedBearerToken` — registers a user, logs in, mangles the last 4 chars of the access token, hits a protected path, asserts 401.
- **Decision**: FIXED

### F10 — CORS allowCredentials(true) likely unnecessary

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/security/SecurityConfig.java
- **Detail**: Refresh tokens are body-borne, not cookie-borne — `allowCredentials(true)` widened CORS semantics without a need.
- **Fix**: Removed `config.setAllowCredentials(true)`.
- **Decision**: FIXED

## Test verification

After all fixes: `./mvnw test` → 22 tests, 0 failures, 0 errors, BUILD SUCCESS. (+1 vs. baseline — new tampered-bearer integration test.)

## Files changed during triage

- `src/main/resources/application.properties` (F1)
- `src/main/java/com/example/finance_hq/security/JwtAuthenticationFilter.java` (F2)
- `src/main/java/com/example/finance_hq/user/RefreshTokenRepository.java` (F3)
- `src/main/java/com/example/finance_hq/auth/RefreshTokenService.java` (F3)
- `src/test/java/com/example/finance_hq/auth/RefreshTokenServiceTest.java` (F3 mock update)
- `src/main/java/com/example/finance_hq/auth/AuthService.java` (F4)
- `src/main/java/com/example/finance_hq/auth/JwtService.java` (F5)
- `src/test/java/com/example/finance_hq/auth/JwtServiceTest.java` (F5 — expiry test threshold)
- `src/main/resources/db/migration/V3__add_refresh_tokens_indexes.sql` (F6 — new)
- `context/foundation/lessons.md` (F7 — appended)
- `src/test/java/com/example/finance_hq/auth/AuthControllerIntegrationTest.java` (F9)
- `src/main/java/com/example/finance_hq/security/SecurityConfig.java` (F10)
