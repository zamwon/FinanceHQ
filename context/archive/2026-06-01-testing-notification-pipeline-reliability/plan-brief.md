# Notification Pipeline Reliability — Plan Brief

> Full plan: `context/changes/testing-notification-pipeline-reliability/plan.md`
> Research: `context/changes/testing-notification-pipeline-reliability/research.md`

## What & Why

Close three test gaps in the notification pipeline: Risk #1 (email fails to arrive), Risk #2 (wrong notification date from edge cases), and Risk #6 (duplicate emails on retry after DB failure). The pipeline is fully implemented but has no integration test against a real DB, one missing compound unit assertion, and a live duplicate-email vulnerability where a failed SENT log write causes the next daily run to re-send.

## Starting Point

The notification pipeline has unit tests for date computation and log repository queries, and mock-based unit tests for `NotificationService`. No test exercises the full path (scheduler → email send → DB log write) against a real PostgreSQL DB. Testcontainers and `@SpringBootTest` are already wired in the project.

## Desired End State

When this plan is done: the duplicate-email vulnerability is closed (PENDING row written before send acts as an idempotency guard); a hermetic test proves the idempotency fix holds under send-success + log-failure; integration tests prove the daily run fires for the right obligations and the retry path works end-to-end; and a compound unit test asserts the 31st-in-February edge case chains correctly through both date components.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Risk #6 fix vs document | Fix (add PENDING status + pre-send idempotency) | Closing the vulnerability is in scope and takes ~5 file changes; documenting broken behavior leaves duplicates in production | Plan |
| DB failure injection | Mockito spy via MockitoExtension (no Spring context) | Hermetic tests are faster and match the existing `NotificationServiceTest` pattern | Plan |
| Integration email mock | `@MockBean JavaMailSender` | Auto-resets between tests; verifying `send()` called is sufficient signal for Risk #1 | Research |
| Retry test scope | Include in Phase 4 | The retry path is entirely untested; leaving it out means a bug in `retryFailedNotifications()` is undetected | Plan |
| Compound edge case location | Inline in `NextDueDateComputerTest` | One test in an existing file is simpler than a new class for a single assertion | Plan |
| Scheduler invocation | Call `runDailyNotifications()` directly | Testing `@Scheduled` annotation wiring is a Spring framework guarantee, not project logic | Plan |

## Scope

**In scope:**
- One compound unit test (31→Feb-28 + business-day chain)
- Idempotency fix: `PENDING` status, `recordPending()`, updated `recordSuccess()` and `recordFailure()`
- Hermetic test for Risk #6 two-run scenario
- Integration tests: daily run (send + SENT log) + far-out obligation skipped + retry path
- Cookbook §6.1 and §6.2 filled

**Out of scope:**
- `@Scheduled` cron annotation testing
- Public holiday support
- Per-user timezone support
- GreenMail / real SMTP verification
- Stale PENDING row cleanup (post-crash recovery)

## Architecture / Approach

The idempotency fix adds a PENDING write before the email send so that even if the SENT update fails, the next run's existing dedup check (`findAlreadyLoggedObligationIds`) detects the PENDING row and skips re-send. `recordSuccess()` and `recordFailure()` switch from INSERT to UPDATE (find by `obligation_id + due_date`, update status field). `runDailyNotifications()` catches `DataIntegrityViolationException` from `recordPending()` to skip already-processed groups gracefully.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Unit — compound date edge case | Compound `NextDueDateComputer` + `BusinessDayCalculator` test for 31→Feb-28 path | Feb 28 is a Saturday in 2026 — wrong day calculation would miss the edge case silently |
| 2. Code fix — idempotency | PENDING status + pre-send write + UPDATE in recordSuccess/Failure | `saveAndFlush()` required (not `save()`) so UNIQUE violation surfaces immediately |
| 3. Hermetic test — Risk #6 | Proves two-run scenario: send once, not twice, when recordSuccess() fails | `doAnswer` side-effect on mock must correctly simulate PENDING row being written |
| 4. Integration test — Risk #1 + retry | Real DB confirms pipeline fires and logs correctly; retry path covered | `@Transactional` rollback isolation; fixed date (June 2 / June 3) avoids CI flakiness |
| 5. Cookbook update | §6.1, §6.2 filled; change.md and test-plan.md §3 updated | None |

**Prerequisites:** Docker available for Testcontainers (already verified in CI)  
**Estimated effort:** ~2 sessions across 5 phases (Phase 2 is the largest: ~5 files)

## Open Risks & Assumptions

- **Stale PENDING rows**: if the service crashes between `recordPending()` and `sendGroupedEmail()`, the row stays PENDING forever and the obligation is never notified. The hourly retry only targets FAILED rows. Acceptable MVP gap — note it in a code comment.
- **Fixed test date (June 2 / June 3)**: integration test uses hardcoded dates to avoid weekend/month-boundary flakiness in CI. Any test run on or after June 3 2026 would have `TODAY.plusDays(1)` resolve to a past date — re-evaluate if the test suite is run well past that date.

## Success Criteria (Summary)

- `./mvnw test` passes on all 5 phases with no new test failures
- Risk #6: `verify(mailSender, times(1))` passes across two consecutive `runDailyNotifications()` calls (not `times(2)`)
- Risk #1: `NotificationLog` row with `status=SENT` present in real DB after `runDailyNotifications()`
