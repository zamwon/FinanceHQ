# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-06-01

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. Do not promote to e2e because e2e "feels safer." Do not put a
   vision model on top of a deterministic visual diff that already catches
   the regression.
2. **User concerns are first-class evidence.** Risks anchored in "the
   team is worried about X, and the failure would surface somewhere in
   this area" carry the same weight as PRD lines or hot-spot data.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is
   produced by `/10x-research` during each rollout phase. If the plan and
   research disagree about where the failure lives, research is the
   ground truth.

Hot-spot scope used for likelihood weighting: `src/main/java`, `src/main/frontend/src`, `src/main/resources`, `src/test` — 64 commits / 30 days.

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact x likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|-------------------------|--------|------------|--------------------------------|
| 1 | Notification email fails to arrive before a payment deadline — scheduler runs but email never reaches the user; a missed payment follows | High | High | PRD Primary Success Criterion ("missed notification = product broken"), FR-007, Interview Q1 items 1+4 |
| 2 | Scheduler computes wrong notification date — timezone mismatch or business-day edge case causes notification to fire on the wrong day | High | Medium | Interview Q1 item 3, PRD Business Logic ("1 day before"), Roadmap S-04 unknown ("1 business day vs 1 calendar day"), hot-spot dir `notification/` (14 commits/30d) |
| 3 | Obligation data silently lost or corrupted after deploy — user adds an obligation, a production deploy runs, and the obligation is gone or has wrong values | High | Medium | PRD NFR ("no data loss or corruption"), Interview Q1 item 2, Interview Q2 item 2 (Flyway/Railway burn), hot-spot dir `db/migration` (10 commits/30d) |
| 4 | Obligation ownership bypass leaks cross-user data (IDOR) — API call with valid auth token but different user's obligation ID returns or modifies that obligation | High | Medium | PRD NFR ("no unauthorized access"), Interview Q4, archive/add-and-list-obligations risk note (ownership enforcement pattern) |
| 5 | Frontend production build diverges from dev — auth flow, routing, or critical UI that works in `ng serve` breaks when Angular prod build is served as static assets by Spring Boot | Medium | High | Interview Q2 item 1 (prod styling burn), Interview Q3 item 1 (Angular low-confidence), hot-spot dir `src/main/frontend/src/app` (26 commits/30d), zero frontend test files |
| 6 | Notification retry produces duplicate emails — email sends successfully but DB write to notification_log fails; next scheduler run re-sends | Medium | Medium | Archive/email-notification-scheduler risk note (transaction boundary: send outside TX, log inside TX), Interview Q3 item 2 |
| 7 | Auth token refresh race condition logs user out — two concurrent 401 responses trigger parallel refresh attempts; serialization fails and session is destroyed | Medium | Low | Archive/angular-spa-scaffold risk note (BehaviorSubject serialization), archive/auth-scaffold risk note (refresh-token rotation as sole replay defense) |

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|------|----------------------------|----------------|--------------------------------------|-----------------------|-----------------------|
| #1 | Obligation due 1 biz day from now triggers scheduler; email send attempted; notification_log records SUCCESS. Obligation due further out does NOT trigger | "Scheduler ran" does not mean "email arrived." "Works for 1 obligation" does not mean "works for 20 with same due date" | Scheduler trigger mechanism, obligation eligibility query, email send boundary, notification_log dedup, retry path | Integration (mocked email transport + real DB via Testcontainers) | Mocking entire notification service and only asserting it was called |
| #2 | Monday due: Friday notify. Tuesday due: Monday notify. 31st in short month: correct clamp + biz day. FIXED_TERM past endDate: no notification | "Business-day calculator is correct" does not mean "scheduler uses it correctly." Calculator may be right but scheduler may use different timezone or clock | How scheduler determines "today," timezone handling (Warsaw cron), business-day calculator API, next-due-date computation interaction | Unit (date computation) + integration (scheduler-uses-calculator) | Testing only weekday-to-weekday; missing month-end clamp + business-day interaction |
| #3 | Obligations created before a migration are readable and correct after; all fields round-trip without silent truncation or type coercion | "App starts after migration" does not mean "existing data is intact." NOT NULL with wrong default silently corrupts | Flyway migration chain, entity field types vs column types, nullable constraints | Integration (seed data, verify read-back via Testcontainers) | Testing only that migration runs without error |
| #4 | Every endpoint accepting an obligation ID returns 404 when authenticated user does not own it — GET, PATCH, DELETE | "Service uses ownership-scoped query" does not mean "every controller path uses that service method." New endpoint or direct repo call bypasses | All controller endpoints accepting obligation IDs, service methods called, whether any repo method exposes unscoped find | Integration (per-endpoint, auth as user A, target user B's obligation) | Testing only happy path (owner accesses own obligation) and assuming non-owner 404 is covered |
| #5 | Angular prod build served by Spring Boot renders login, allows login, shows dashboard, navigates to obligations — same flow as `ng serve` | "`ng build` succeeds" does not mean "built app works." Tree-shaking, AOT, CSS processing, SPA forwarding can each break silently | Angular build pipeline (AOT, CSS processing), Spring SPA forwarding config, static asset serving, env-specific config diffs | E2E smoke (Playwright against prod build served by Spring Boot) | Testing only against `ng serve` — dev server is not the production runtime |
| #6 | If email sends but notification_log write fails, next scheduler run re-queries but deduplication prevents second email for same obligation + due_date | "UNIQUE constraint exists" does not mean "duplicates prevented." Constraint fires at INSERT; if service sends first and logs second, retry sends before hitting constraint | Transaction boundary (send outside TX, log inside TX), UNIQUE constraint, retry query (checks existing SENT logs?), constraint violation handling | Integration (simulate send-success + DB-failure, run scheduler again, verify no duplicate send) | Mocking email sender to always succeed and asserting log written — tests happy path, not failure boundary |
| #7 | Two concurrent 401s trigger only one refresh-token request; both original requests retry with new token; session continues | "BehaviorSubject serializes" does not mean "user stays logged in." If first refresh fails, second queued request must also fail gracefully — not retry in a loop | Angular authInterceptor, BehaviorSubject gating pattern, refresh-failure handling, token storage lifecycle | Unit (interceptor with mocked HttpClient) | Testing only single-request refresh; missing concurrent-401 scenario |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|------------|-----------------|---------------|------------|--------|---------------|
| 1 | Notification pipeline reliability | Prove notification path works: right date, right delivery, no duplicates, correct retry | #1, #2, #6 | unit + integration | complete | testing-notification-pipeline-reliability |
| 2 | Data integrity and access control | Prove obligations persist correctly and are never leaked cross-user | #3, #4 | integration | not started | — |
| 3 | Frontend production parity | Prove Angular prod build served by Spring Boot works: auth flow, routing, critical UI, token refresh | #5, #7 | e2e (Playwright) + unit (interceptor) | not started | — |
| 4 | Quality gates wiring | Lock the test floor so regressions cannot merge | cross-cutting | CI gates | not started | — |

## 4. Stack

| Layer | Tool | Version | Notes |
|-------|------|---------|-------|
| unit + integration | JUnit Jupiter + Spring Boot Test + Testcontainers | Spring Boot 4.0.6 | `@SpringBootTest` with `@ServiceConnection` PostgreSQL; `./mvnw test` |
| API mocking | Mockito | (bundled with Spring Boot) | Service-layer mocks; email transport mock for notification tests |
| e2e | none yet — see §3 Phase 3 | — | Playwright MCP available in session; wire during Phase 3 |
| frontend unit | none yet — see §3 Phase 3 | — | Angular 21 has no test runner configured; zero spec files |
| (optional) browser automation | Playwright MCP — checked: 2026-06-01 | n/a | When NOT to use: deterministic assertions already catch the regression; use only for prod-build smoke where DOM interaction is required |

**Stack grounding tools (current session):**
- Docs: Context7 — available; checked: 2026-06-01
- Search: WebSearch — available; checked: 2026-06-01
- Runtime/browser: Playwright MCP — available (browser automation for e2e smoke on prod build); checked: 2026-06-01
- Provider/platform: none available in current session

## 5. Quality Gates

| Gate | Where | Required? | Catches |
|------|-------|-----------|---------|
| unit + integration | local + CI | required after §3 Phase 1 | logic regressions in notification pipeline, data integrity, access control |
| e2e on critical flows | CI on PR | required after §3 Phase 3 | broken auth flow, routing, or critical UI in prod build |
| pre-push hook | local | recommended after §3 Phase 4 | regressions caught before push |
| CI test step (GitHub Actions) | CI on PR + merge | required after §3 Phase 4 | all test suites must pass before merge |

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once
the relevant rollout phase ships; before that, the sub-section reads
"TBD — see §3 Phase N."

### 6.1 Adding a unit test for date/business-day logic

Use `@ParameterizedTest` with `@CsvSource` for weekday-transition rules — each row is one input/expected pair named `"input -> expected"`. This format makes test output self-describing and catches regressions from adding a new rule without reading the full test.

For **compound scenarios** (month-end clamp followed by business-day skip), use a named `@Test` method instead of `@CsvSource`. The method name encodes the oracle: `recurring_paymentDay31_inFebruary_notifyDateIsFebruary27`. Assert both intermediate and final results in sequence so a failure tells you exactly which step broke.

Oracle source: PRD Business Logic ("1 day before each obligation's next due date") and `NextDueDateComputer`'s month-end clamp contract. Never derive the expected value from the implementation — use calendar facts (Feb 2026 has 28 days; Feb 28, 2026 is Saturday).

Representative compound test (from `NextDueDateComputerTest`):

```java
@Test
void recurring_paymentDay31_inFebruary_notifyDateIsFebruary27() {
    // Feb 28, 2026 is a Saturday; previousBusinessDay must skip it and land on Friday Feb 27
    LocalDate today = LocalDate.of(2026, 2, 1);
    LocalDate dueDate = NextDueDateComputer.compute(31, today, ObligationPeriod.RECURRING, null);
    assertThat(dueDate).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(BusinessDayCalculator.previousBusinessDay(dueDate)).isEqualTo(LocalDate.of(2026, 2, 27));
}
```

### 6.2 Adding an integration test for a service + DB flow

Class-level setup:

```java
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class MyServiceIntegrationTest { ... }
```

`@Transactional` rolls back each test automatically — no manual cleanup needed and no residual rows between tests.

For external calls with irreversible side effects (email, SMS, webhooks), use `@MockitoBean` (not `@Autowired`) to replace the real bean with a Mockito mock:

```java
@MockitoBean JavaMailSender mailSender;
```

Pattern: seed data in `@BeforeEach`, call the service method under test, assert both the external call (`verify(mailSender, times(1)).send(...)`) and the resulting DB state (repository query with specific status/field assertion, not just count).

Reference implementation: `NotificationServiceIntegrationTest` — covers daily run happy path, date filter (no send), and retry path (FAILED → SENT).

### 6.3 Adding an IDOR boundary test for an API endpoint

TBD — see §3 Phase 2 for per-endpoint ownership enforcement pattern.

### 6.4 Adding an e2e smoke test for the prod build

TBD — see §3 Phase 3 for Playwright-based prod build smoke pattern.

### 6.5 Per-rollout-phase notes

(Filled in after each phase lands.)

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **Flyway migration SQL** — migration either runs or fails; testing the SQL itself adds no signal above the integration tests that exercise the schema. Re-evaluate if migrations start doing data transformations (not just DDL). (Source: Phase 2 interview Q5.)
- **Admin/internal endpoints** — none exist in this MVP. Re-evaluate if admin features are added. (Source: Phase 2 interview Q5.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-06-01
- Stack versions last verified: 2026-06-01
- AI-native tool references last verified: 2026-06-01

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
