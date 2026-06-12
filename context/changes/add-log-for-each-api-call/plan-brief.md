# Add SLF4J Info Log to Each Controller Method — Plan Brief

> Full plan: `context/changes/add-log-for-each-api-call/plan.md`

## What & Why

Add one `log.info()` line at the entry of every controller method so each API call is traceable in logs. The immediate motivation is observability: currently there is zero `INFO`-level logging for any business operation, making it impossible to tell from logs which endpoints are being hit and by whom.

## Starting Point

Six of the seven controllers have no logger at all. `SentryTunnelController` is the one exception — it uses Lombok's `@Slf4j` and is the established pattern to follow. No `maskEmail()` utility exists yet.

## Desired End State

Every controller method emits one INFO line — naming the action and the masked caller — before delegating to the service. `SentryTunnelController` is left untouched. A log like `Started login call as b***@gmail.com` or `Started update obligation 3f2a… as b***@gmail.com` appears in stdout for every API call.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
|---|---|---|
| maskEmail() placement | Shared static utility (`LogMaskingUtils`) | DRY — one place to change masking logic across all controllers |
| Email mask format | `b***@gmail.com` | Enough to identify the caller while hiding most of the address |
| refresh / logout log | Action name only, no identity | No email is available at the time of these token-only calls |
| SentryTunnelController | Skip | It's a high-frequency infra proxy, already has its own logging |

## Scope

**In scope:**
- New `LogMaskingUtils.maskEmail()` utility in `com.example.finance_hq.util`
- `@Slf4j` + `log.info()` added to: `AuthController`, `ObligationController`, `PortfolioAssetController`, `PortfolioPriceController`, `DashboardController`, `TransactionController`

**Out of scope:**
- `SentryTunnelController` — no changes
- Request payload logging
- AOP / filter-based logging
- Masking of refresh tokens

## Architecture / Approach

One new class (`LogMaskingUtils`) provides the shared `maskEmail()` static method. Each controller adds `@Slf4j` (Lombok) and a static import of `maskEmail`. Auth endpoints read the email from the DTO record (`.email()` accessor); authenticated endpoints read from `@AuthenticationPrincipal User user` via `.getEmail()`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Create LogMaskingUtils | Shared utility compiles and masks correctly | Masking edge cases (null, missing `@`) must be handled |
| 2. Wire logging into all 6 controllers | Every method logs before its service call | Wrong accessor on auth DTOs (records use `.email()`, not `.getEmail()`) |

**Prerequisites:** App runs locally with `--spring.profiles.active=local`  
**Estimated effort:** ~1 session, 2 phases

## Open Risks & Assumptions

- `User.getEmail()` confirmed at line 72 — safe to call on any `@AuthenticationPrincipal` user
- Auth DTOs are Java records — accessor is `req.email()`, not `req.getEmail()`; misuse would be a compile error

## Success Criteria (Summary)

- `./mvnw test` passes after all changes
- `POST /auth/login` emits `Started login call as b***@gmail.com` in stdout
- `GET /api/obligations` emits `Started list obligations as b***@gmail.com` in stdout
