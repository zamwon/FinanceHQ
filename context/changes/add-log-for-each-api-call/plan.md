# Add SLF4J Info Log to Each Controller Method — Implementation Plan

## Overview

Add a `log.info()` entry line at the start of every controller method, before the service call. Auth endpoints log the email from the request DTO; authenticated endpoints log the email from the `@AuthenticationPrincipal` user. Email is masked to `b***@gmail.com` format via a shared utility.

## Current State Analysis

Seven controllers exist in the codebase. Only `SentryTunnelController` already has logging (`@Slf4j` + `log.warn/debug`). The remaining six have no logger at all:

- `AuthController` — 4 methods (register, login, refresh, logout); no principal
- `ObligationController` — 5 methods; all use `@AuthenticationPrincipal User user`
- `PortfolioAssetController` — 5 methods; all use `@AuthenticationPrincipal User user`
- `PortfolioPriceController` — 1 method; uses `@AuthenticationPrincipal User user`
- `DashboardController` — 2 methods; both use `@AuthenticationPrincipal User user`
- `TransactionController` — 4 methods; all use `@AuthenticationPrincipal User user`

Lombok is in `pom.xml`; `@Slf4j` is the project's established annotation (SentryTunnelController). `User.getEmail()` getter exists at line 72. Auth DTOs (`LoginRequest`, `RegisterRequest`) are Java records — accessor is `req.email()`, not `req.getEmail()`. `RefreshRequest` and `LogoutRequest` contain only a refresh token (no email).

## Desired End State

Every controller method emits one `INFO` log line before delegating to the service. The line names the action and the masked caller identity (or just the action name for token-only endpoints). `SentryTunnelController` is left untouched.

**Verify by**: starting the app and hitting any endpoint — the corresponding log line appears in stdout.

### Key Discoveries

- `src/main/java/com/example/finance_hq/web/SentryTunnelController.java:22` — `@Slf4j` is the pattern; no manual `LoggerFactory` call needed
- `src/main/java/com/example/finance_hq/user/User.java:72` — `getEmail()` getter confirmed
- Auth DTOs are records: `req.email()` not `req.getEmail()`
- No `util` package exists yet — must be created

## What We're NOT Doing

- No logging added to `SentryTunnelController` (already logged, high-frequency infra endpoint)
- No logging of request payloads, headers, or path variables beyond the resource ID where it aids traceability
- No masking of refresh tokens (refresh/logout just log the action name)
- No AOP/interceptor approach — logs are placed directly in each method for explicitness

## Implementation Approach

1. Create `LogMaskingUtils` with a static `maskEmail()` helper used by all controllers.
2. Add `@Slf4j` and a `log.info()` first line to each of the six controllers.

---

## Phase 1: Create LogMaskingUtils Utility

### Overview

Introduce a single `maskEmail()` utility in a new `util` package so all controllers share the same masking logic.

### Changes Required

#### 1. New utility class

**File**: `src/main/java/com/example/finance_hq/util/LogMaskingUtils.java`

**Intent**: Provide a package-private static helper that reduces an email to `b***@gmail.com` format — enough to identify the caller in logs without exposing the full address.

**Contract**:
```java
package com.example.finance_hq.util;

public final class LogMaskingUtils {

    private LogMaskingUtils() {}

    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "[masked]";
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        if (local.isEmpty()) {
            return "[masked]";
        }
        return local.charAt(0) + "***" + email.substring(at);
    }
}
```

The snippet is included because the exact masking contract (keep first char + `***` + full domain including `@`) is what was decided in planning — the implementer should not guess the format.

### Success Criteria

#### Automated Verification

- Build passes: `./mvnw test -pl . -q`

#### Manual Verification

- `maskEmail("blazej@gmail.com")` → `"b***@gmail.com"`
- `maskEmail(null)` → `"[masked]"`
- `maskEmail("@gmail.com")` → `"[masked]"`

**Implementation Note**: Pause here and confirm the utility compiles before wiring it into controllers.

---

## Phase 2: Wire Logging Into All Six Controllers

### Overview

Add `@Slf4j` annotation and one `log.info()` call at the top of every mapped method across the six controllers. Use a static import of `LogMaskingUtils.maskEmail` to keep call sites concise.

### Changes Required

#### 1. AuthController

**File**: `src/main/java/com/example/finance_hq/auth/AuthController.java`

**Intent**: Add `@Slf4j` and log each auth action. `register` and `login` have the email in the DTO; `refresh` and `logout` carry only a token, so just log the action name.

**Contract**: Add `import lombok.extern.slf4j.Slf4j;` and `import static com.example.finance_hq.util.LogMaskingUtils.maskEmail;`. Annotate the class with `@Slf4j`. Insert before each service call:

- `register` → `log.info("Started register call as {}", maskEmail(req.email()));`
- `login` → `log.info("Started login call as {}", maskEmail(req.email()));`
- `refresh` → `log.info("Started refresh call");`
- `logout` → `log.info("Started logout call");`

#### 2. ObligationController

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationController.java`

**Intent**: Add `@Slf4j` and log each obligation action with the acting user's masked email.

**Contract**: Same imports + `@Slf4j`. Insert before each service call:

- `list` → `log.info("Started list obligations as {}", maskEmail(user.getEmail()));`
- `create` → `log.info("Started create obligation as {}", maskEmail(user.getEmail()));`
- `update` → `log.info("Started update obligation {} as {}", id, maskEmail(user.getEmail()));`
- `delete` → `log.info("Started delete obligation {} as {}", id, maskEmail(user.getEmail()));`
- `pay` → `log.info("Started pay obligation {} as {}", id, maskEmail(user.getEmail()));`

#### 3. PortfolioAssetController

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioAssetController.java`

**Intent**: Add `@Slf4j` and log each portfolio asset action. The `importCsv` method has guard branches before the service call — the log goes before all guards, right at method entry.

**Contract**: Same imports + `@Slf4j`. Insert at the very start of each method body:

- `list` → `log.info("Started list portfolio assets as {}", maskEmail(user.getEmail()));`
- `create` → `log.info("Started create portfolio asset as {}", maskEmail(user.getEmail()));`
- `update` → `log.info("Started update portfolio asset {} as {}", id, maskEmail(user.getEmail()));`
- `delete` → `log.info("Started delete portfolio asset {} as {}", id, maskEmail(user.getEmail()));`
- `importCsv` → `log.info("Started import CSV as {}", maskEmail(user.getEmail()));`

#### 4. PortfolioPriceController

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioPriceController.java`

**Intent**: Add `@Slf4j` and log the price refresh action.

**Contract**: Same imports + `@Slf4j`. Insert before the service call:

- `refreshPrices` → `log.info("Started refresh prices as {}", maskEmail(user.getEmail()));`

#### 5. DashboardController

**File**: `src/main/java/com/example/finance_hq/dashboard/DashboardController.java`

**Intent**: Add `@Slf4j` and log each dashboard query.

**Contract**: Same imports + `@Slf4j`. Insert before each service call:

- `getSummary` → `log.info("Started get dashboard summary as {}", maskEmail(user.getEmail()));`
- `getTrends` → `log.info("Started get dashboard trends as {}", maskEmail(user.getEmail()));`

#### 6. TransactionController

**File**: `src/main/java/com/example/finance_hq/transaction/TransactionController.java`

**Intent**: Add `@Slf4j` and log each transaction action.

**Contract**: Same imports + `@Slf4j`. Insert before each service call:

- `list` → `log.info("Started list transactions as {}", maskEmail(user.getEmail()));`
- `create` → `log.info("Started create transaction as {}", maskEmail(user.getEmail()));`
- `update` → `log.info("Started update transaction {} as {}", id, maskEmail(user.getEmail()));`
- `delete` → `log.info("Started delete transaction {} as {}", id, maskEmail(user.getEmail()));`

### Success Criteria

#### Automated Verification

- Build and tests pass: `./mvnw test`

#### Manual Verification

- Start the app: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
- Hit `POST /auth/login` — log line `Started login call as b***@gmail.com` appears in stdout
- Hit any authenticated endpoint (e.g., `GET /api/obligations`) — corresponding log line appears with masked email
- `POST /sentry-tunnel` — no new `Started …` line (SentryTunnelController untouched)

---

## Testing Strategy

### Automated

- Existing test suite covers controller behavior; no new tests required for this purely additive change.

### Manual Testing Steps

1. `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
2. Call `POST /auth/register` → verify `Started register call as t***@example.com` in console
3. Call `POST /auth/login` → verify `Started login call as t***@example.com`
4. Call `POST /auth/refresh` → verify `Started refresh call` (no email)
5. Call `GET /api/obligations` with valid token → verify `Started list obligations as t***@example.com`
6. Call `PATCH /api/obligations/{id}` → verify log includes the UUID and masked email
7. Confirm no duplicate or extraneous log lines

## References

- Established `@Slf4j` pattern: `src/main/java/com/example/finance_hq/web/SentryTunnelController.java:22`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Create LogMaskingUtils Utility

#### Automated

- [x] 1.1 Build passes after creating LogMaskingUtils: `./mvnw test -pl . -q`

#### Manual

- [ ] 1.2 `maskEmail("blazej@gmail.com")` returns `"b***@gmail.com"`
- [ ] 1.3 `maskEmail(null)` returns `"[masked]"`
- [ ] 1.4 `maskEmail("@gmail.com")` returns `"[masked]"`

### Phase 2: Wire Logging Into All Six Controllers

#### Automated

- [ ] 2.1 Build and full test suite pass: `./mvnw test`

#### Manual

- [ ] 2.2 POST /auth/login emits `Started login call as b***@gmail.com`
- [ ] 2.3 POST /auth/refresh emits `Started refresh call` (no email)
- [ ] 2.4 GET /api/obligations emits `Started list obligations as b***@gmail.com`
- [ ] 2.5 PATCH /api/obligations/{id} log includes UUID and masked email
- [ ] 2.6 POST /sentry-tunnel emits no new `Started …` line
