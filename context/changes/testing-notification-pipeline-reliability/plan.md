# Notification Pipeline Reliability — Implementation Plan

## Overview

Close the three notification-pipeline test gaps from `context/foundation/test-plan.md` Phase 1 (Risks #1, #2, #6). One code change fixes the duplicate-email vulnerability; three test phases prove the pipeline is reliable. The existing test infrastructure (Testcontainers, @SpringBootTest, MockitoExtension) is fully wired — no infra setup required.

## Current State Analysis

The notification pipeline is fully implemented:
- `NotificationScheduler` fires at 8 AM Europe/Warsaw; hourly retry for FAILED rows
- `BusinessDayCalculator.previousBusinessDay()` skips weekends; no public holiday support
- `NextDueDateComputer.compute()` handles month-end clamping and FIXED_TERM end-date guards
- `NotificationService.runDailyNotifications()` filters eligible obligations, groups by (user, dueDate), sends grouped email, records SENT or FAILED log
- UNIQUE(obligation_id, due_date) constraint prevents duplicate log rows

Existing test coverage: unit tests exist for `BusinessDayCalculator`, `NextDueDateComputer`, `NotificationLogRepository` (UNIQUE constraint + queries), `NotificationService` (7 mock-based unit tests), and `NotificationPersistenceService` (FIXED_TERM decrement). No integration test exercises the full pipeline against a real DB.

**Critical gap (Risk #6):** Email send and log write are in separate transactions. If the SENT log write fails after a successful send, the next daily run sees no log row, treats the obligation as unsent, and re-sends — producing a duplicate email.

## Desired End State

After this plan:
- One compound unit assertion chains month-end clamping + business-day calculator for the 31st-in-February edge case (Risk #2).
- The duplicate-email vulnerability is closed: a PENDING row is written before each send; if the SENT update fails, the next run detects the PENDING row and skips re-send (Risk #6).
- One integration test proves `runDailyNotifications()` against a real DB: correct obligation fires, SENT log written, far-out obligation skipped (Risk #1).
- One integration test proves the retry path: FAILED rows are re-sent by `retryFailedNotifications()` and updated to SENT (Risk #1).
- Cookbook §6.1 and §6.2 filled with the patterns established here.

### Key Discoveries

- Test infrastructure is already wired: `TestcontainersConfiguration` (PostgreSQL 16-alpine, `@ServiceConnection`), `@ActiveProfiles("test")`, `application-test.properties` with JWT config — `context/changes/testing-notification-pipeline-reliability/research.md`
- `NotificationService` is constructed manually (no `@Service` wiring in unit tests): `new NotificationService(obligationService, notificationLogRepository, persistenceService, mailSender, "from@example.com")` — `NotificationServiceTest.java:46`
- `recordSuccess()` currently does `notificationLogRepository.save(new NotificationLog(...))` — this INSERT will hit the UNIQUE constraint once a PENDING row exists; must change to UPDATE — `NotificationPersistenceService.java:26-35`
- `recordFailure()` has the same INSERT-vs-UPDATE problem: must also UPDATE the PENDING row to FAILED after the fix — `NotificationPersistenceService.java:37-43`
- Retry path (`retryFailedNotifications()`) uses `markRetrySuccess()` which updates existing rows (already correct) — `NotificationPersistenceService.java:47-56`
- `findAlreadyLoggedObligationIds` queries by `dueDate` with no status filter — PENDING rows will be caught by this check, which is the dedup mechanism — `NotificationLogRepository.java:15`

## What We're NOT Doing

- Not testing the `@Scheduled` cron annotation wiring (Spring framework guarantee)
- Not adding public-holiday support to `BusinessDayCalculator` (out of scope for v0.1)
- Not adding per-user timezone support (Warsaw hardcoded, acceptable for MVP)
- Not adding GreenMail or real SMTP verification (`send()` called is sufficient signal for Risk #1)
- Not handling stale PENDING rows (obligation stuck in PENDING after a mid-send crash — acceptable MVP limitation; retry only targets FAILED rows)

## Implementation Approach

Phases run in dependency order: unit edge case first (zero risk), then the code fix (idempotency), then hermetic test that relies on the fixed service logic, then integration tests that require a real DB. Cookbook update is last.

## Critical Implementation Details

**recordSuccess() and recordFailure() must UPDATE, not INSERT.** After `recordPending()` writes a PENDING row, both methods must locate the existing row by `(obligation_id, due_date)` and update its `status` field. A second `save(new NotificationLog(...))` will throw `DataIntegrityViolationException` on the UNIQUE constraint. Use the new `findByObligationIdAndDueDate()` repository method to fetch the row before updating.

**DataIntegrityViolationException catch in runDailyNotifications().** If `recordPending()` throws `DataIntegrityViolationException`, a PENDING (or SENT) row already exists for this group — it was processed in a prior run. Catch this exception per group, log at INFO level, and skip the send. Do not re-throw; the obligation is not lost.

---

## Phase 1: Unit — Compound Date Edge Case

### Overview

The 31st-in-February scenario is unit-tested in isolation (clamping in `NextDueDateComputerTest`, weekday transitions in `BusinessDayCalculatorTest`), but the two are never chained. This phase adds one assertion that exercises both in sequence to prove Risk #2's most subtle path.

### Changes Required

#### 1. Compound chain test

**File**: `src/test/java/com/example/finance_hq/obligation/NextDueDateComputerTest.java`

**Intent**: Add one test that feeds a paymentDay=31 obligation in February through `compute()` then immediately through `previousBusinessDay()`, asserting both the month-end clamp and the resulting notify date in a single chain.

**Contract**: New `@Test` method `recurring_paymentDay31_inFebruary_notifyDateIsFebruary27`. Seed: paymentDay=31, today=2026-02-01, RECURRING, endDate=null. Assert: `compute()` returns 2026-02-28; `previousBusinessDay(result)` returns 2026-02-27. February 28 is a Saturday in 2026, so `previousBusinessDay` must skip it and land on Friday February 27.

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=NextDueDateComputerTest` passes with the new test included

#### Manual Verification

- Read the new test name in the test output and confirm it expresses the Feb-28-Saturday edge case clearly

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Phase 2: Code Fix — Idempotency for Risk #6

### Overview

Add a PENDING log row written before each email send. If the SENT/FAILED update fails after send, the PENDING row survives and the next run's dedup check (`findAlreadyLoggedObligationIds`) detects it, preventing re-send. Requires a new enum value, a new repository method, a new service method, and changes to two existing persistence methods.

### Changes Required

#### 1. Add PENDING status

**File**: `src/main/java/com/example/finance_hq/notification/NotificationStatus.java`

**Intent**: Add `PENDING` as a new status value representing "row written but send not yet confirmed." The VARCHAR(20) DB column accepts any string value — no migration needed.

**Contract**: Add `PENDING` to the enum before `SENT` and `FAILED`.

---

#### 2. Add repository finder by obligation + due date

**File**: `src/main/java/com/example/finance_hq/notification/NotificationLogRepository.java`

**Intent**: `recordSuccess()` and `recordFailure()` need to locate the existing PENDING row to update it. Spring Data JPA can derive this query from the method name.

**Contract**: Add `Optional<NotificationLog> findByObligationIdAndDueDate(UUID obligationId, LocalDate dueDate)`.

---

#### 3. Add recordPending() and update recordSuccess() / recordFailure()

**File**: `src/main/java/com/example/finance_hq/notification/NotificationPersistenceService.java`

**Intent**: Add `recordPending()` that inserts one PENDING row per target using `saveAndFlush()` so the UNIQUE constraint violation surfaces immediately as `DataIntegrityViolationException` (not deferred to commit). Modify `recordSuccess()` to find the existing PENDING row and update its status to SENT and set `sentAt`. Modify `recordFailure()` to find the existing PENDING row and update its status to FAILED.

**Contract**:
- `public void recordPending(List<ObligationService.SchedulerTarget> targets)` — `@Transactional`; for each target: `new NotificationLog(obligation, dueDate, PENDING)` → `saveAndFlush()`
- `recordSuccess()` — replace `save(new NotificationLog(...))` with: fetch via `findByObligationIdAndDueDate()`, set `status=SENT` and `sentAt=now`, then `save()`
- `recordFailure()` — same shape as updated `recordSuccess()` but sets `status=FAILED`

---

#### 4. Call recordPending() before send in the daily run

**File**: `src/main/java/com/example/finance_hq/notification/NotificationService.java`

**Intent**: In `runDailyNotifications()`, before `sendGroupedEmail()`, call `persistenceService.recordPending()` for the current group. If it throws `DataIntegrityViolationException` (obligation already has a PENDING or SENT row from a prior run), skip the group without sending. This closes the window where an unwritten log row causes a re-send.

**Contract**: Wrap the existing group-processing block in a try-catch that catches `DataIntegrityViolationException` from `recordPending()`. On that exception: log at INFO level and `return` (skip group). The existing `MailException` catch remains unchanged. No change to `retryFailedNotifications()`.

---

#### 5. Update unit tests broken by the persistence contract change

**File**: `src/test/java/com/example/finance_hq/notification/NotificationServiceTest.java`

**Intent**: The existing unit tests mock `persistenceService` as a full mock — they remain valid. However, `skipsObligationWhenLogAlreadyExists` may need review: the dedup check now serves as one of two skip paths (the other being `DataIntegrityViolationException` from `recordPending()`). Verify all 7 existing tests still pass after the service change.

**Contract**: No new tests in this file in this phase — only confirm existing tests compile and pass with the updated `NotificationService` constructor or method signatures.

**File**: `src/test/java/com/example/finance_hq/notification/NotificationPersistenceServiceTest.java`

**Intent**: `recordSuccess_decrementsRemainingPaymentsForFixedTerm` and `recordSuccess_doesNotDecrementForRecurring` call `service.recordSuccess()` directly against a mocked repository. After the change to `recordSuccess()` (now calls `findByObligationIdAndDueDate()` before saving), these tests must stub the new finder. Update them so the mock returns a pre-built `NotificationLog` with status=PENDING when `findByObligationIdAndDueDate()` is called.

**Contract**: Add `when(notificationLogRepository.findByObligationIdAndDueDate(any(), any())).thenReturn(Optional.of(pendingLog))` in each existing test's setup, where `pendingLog` is a `NotificationLog` built with status=PENDING.

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=NotificationServiceTest` passes (all 7 existing tests)
- `./mvnw test -Dtest=NotificationPersistenceServiceTest` passes (updated stubs)
- `./mvnw test` passes (full suite — confirms no other test broken by the persistence change)

#### Manual Verification

- Code review: `recordPending()` uses `saveAndFlush()` (not `save()`) — UNIQUE violation must surface immediately
- Code review: `runDailyNotifications()` catches `DataIntegrityViolationException` from `recordPending()` only, not from the broader try block containing `sendGroupedEmail()`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Phase 3: Hermetic Test — Risk #6 (send-success + log-failure)

### Overview

Prove that after the idempotency fix, a second call to `runDailyNotifications()` does NOT re-send the email when the first run wrote a PENDING row but failed to update it to SENT. Uses MockitoExtension (no Spring context, no DB) so the test runs in milliseconds.

### Changes Required

#### 1. Hermetic idempotency test

**File**: `src/test/java/com/example/finance_hq/notification/NotificationServiceTest.java`

**Intent**: Add a new test that exercises the two-run scenario: run 1 writes PENDING and sends email but fails on `recordSuccess()`; run 2 detects the PENDING row (via `findAlreadyLoggedObligationIds`) and skips send.

**Contract**: New `@Test` method `doesNotResendWhenPendingRowExistsFromPriorFailedRun`.

Setup (no Spring context — MockitoExtension only):
- `persistenceService` remains a full Mockito mock (same as existing tests)
- `recordPending()` is stubbed with `doAnswer` to update a mutable `Set<UUID>` variable capturing the obligation IDs that have been "written" to the mock DB
- `notificationLogRepository.findAlreadyLoggedObligationIds(any())` is stubbed with `thenAnswer` returning the mutable Set — so after run 1 calls `recordPending()`, run 2's dedup check returns the obligation ID
- `recordSuccess()` is stubbed to throw `RuntimeException("DB down")` on the first call
- `obligationService.findAllSchedulerTargets(TODAY)` returns the same one-obligation list for both runs

Execution: call `service.runDailyNotifications(TODAY)` twice.

Assertion: `verify(mailSender, times(1)).send(any(SimpleMailMessage.class))` — send called exactly once, not twice.

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=NotificationServiceTest` passes including the new test
- The new test name appears in output as `doesNotResendWhenPendingRowExistsFromPriorFailedRun`

#### Manual Verification

- Read the test: confirm the `doAnswer` side-effect correctly simulates "PENDING row written to mocked DB"
- Confirm `verify(mailSender, times(1))` is the load-bearing assertion (not `times(2)`)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Phase 4: Integration Tests — Risk #1 + Retry Path

### Overview

New `@SpringBootTest` test class using real PostgreSQL (Testcontainers) and `@MockBean JavaMailSender`. Three tests cover the full happy path of the daily run and the retry mechanism — the layer no existing test reaches.

### Changes Required

#### 1. Integration test class

**File**: `src/test/java/com/example/finance_hq/notification/NotificationServiceIntegrationTest.java`

**Intent**: Create a new integration test class following the same pattern as `NotificationLogRepositoryTest` (`@SpringBootTest`, `@Import(TestcontainersConfiguration.class)`, `@ActiveProfiles("test")`, `@Transactional`). Declare `@MockBean JavaMailSender mailSender` so Spring replaces the auto-configured bean; capture send() calls via `ArgumentCaptor<SimpleMailMessage>` or `verify()`.

**Contract**: Class-level annotations:
```
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
```

Inject: `@Autowired NotificationService notificationService`, `@Autowired NotificationLogRepository notificationLogRepository`, `@Autowired UserRepository userRepository`, `@Autowired ObligationRepository obligationRepository`, `@MockBean JavaMailSender mailSender`.

`@BeforeEach`: save a `User("notif-test@example.com", "hash")` via `userRepository`; this user is reused across all three tests. `@Transactional` ensures each test rolls back.

---

**Test 1**: `obligationDueTomorrow_sendsEmailAndWritesSentLog`

**Intent**: Prove Risk #1 — an obligation whose next due date is exactly one business day away triggers `runDailyNotifications()` to send one email and write a SENT log row.

**Contract**:
- TODAY = `LocalDate.now()` (or a fixed date if clock is injected — use a date where `previousBusinessDay(TODAY.plusDays(1)) == TODAY`)
- Seed: `Obligation(user, "Rent", 500, ESSENTIAL, RECURRING, paymentDay=TODAY.plusDays(1).getDayOfMonth(), null, null)` saved via `obligationRepository`
- Call: `notificationService.runDailyNotifications(TODAY)`
- Assert: `verify(mailSender, times(1)).send(any(SimpleMailMessage.class))`
- Assert: one `NotificationLog` row exists in DB with `status=SENT` and `sentAt` not null

Note: Use `TODAY = LocalDate.of(2026, 6, 2)` (Monday) with `paymentDay = 3` (Tuesday June 3) — `previousBusinessDay(June 3) = June 2`. This avoids weekend/month-end edge cases in CI.

---

**Test 2**: `obligationDueFurtherOut_doesNotSendEmail`

**Intent**: Prove the date filter works — an obligation due three calendar days from now must NOT trigger a send.

**Contract**:
- Same user; obligation with `paymentDay = TODAY.plusDays(3).getDayOfMonth()` (3 calendar days out, not 1 business day)
- Call: `notificationService.runDailyNotifications(TODAY)`
- Assert: `verify(mailSender, never()).send(any(SimpleMailMessage.class))`
- Assert: `notificationLogRepository.count() == 0`

---

**Test 3**: `failedNotificationLog_retriedAndUpdatedToSent`

**Intent**: Prove the retry path — a pre-existing FAILED log row triggers `retryFailedNotifications()` to resend and update the row to SENT.

**Contract**:
- Same user + obligation (saved in `@BeforeEach` plus obligation saved inline)
- Seed a `NotificationLog(obligation, dueDate=TODAY.plusDays(1), FAILED)` directly via `notificationLogRepository`
- Call: `notificationService.retryFailedNotifications()`
- Assert: `verify(mailSender, times(1)).send(any(SimpleMailMessage.class))`
- Assert: the `NotificationLog` row's status is now `SENT` and `sentAt` is not null

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=NotificationServiceIntegrationTest` passes all three tests
- `./mvnw test` passes (full suite confirms no regressions)

#### Manual Verification

- Confirm `@Transactional` on the class causes each test to roll back — no residual rows between tests
- Confirm `@MockBean JavaMailSender` is present (not `@Autowired`) — no real SMTP call is made
- Test 1: verify the `NotificationLog` assertion queries by `status=SENT`, not just count

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Phase 5: Cookbook Update

### Overview

Fill the two TBD cookbook sections in `test-plan.md` and update change tracking.

### Changes Required

#### 1. Fill §6.1 — unit test pattern for date/business-day logic

**File**: `context/foundation/test-plan.md`

**Intent**: Replace `TBD — see §3 Phase 1` in §6.1 with a one-paragraph description of the pattern: parameterized `@CsvSource` for weekday transitions; named `@Test` methods for compound scenarios (clamp → business day); oracle from PRD Business Logic ("1 day before") and `NextDueDateComputer` contract.

**Contract**: Prose description + one representative code example (the compound test from Phase 1).

---

#### 2. Fill §6.2 — integration test pattern for service + DB flow

**File**: `context/foundation/test-plan.md`

**Intent**: Replace `TBD — see §3 Phase 1` in §6.2 with the established pattern: `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` + `@ActiveProfiles("test")` + `@Transactional`; `@MockBean` for irreversible external calls (email); seed data in `@BeforeEach`; assert both the external call (verify) and the DB state (repository query).

**Contract**: Prose description of the setup pattern. No code snippet — the test class path (`NotificationServiceIntegrationTest`) is the reference.

---

#### 3. Update change.md status

**File**: `context/changes/testing-notification-pipeline-reliability/change.md`

**Intent**: Advance status from `planned` to `implemented` once all phases complete.

**Contract**: Set `status: implemented` and `updated: <date of completion>`.

---

#### 4. Update test-plan.md §3 Phase 1 status

**File**: `context/foundation/test-plan.md`

**Intent**: Mark Phase 1 row as `complete` in the Phased Rollout table.

**Contract**: Change `change opened` to `complete` in the Phase 1 row.

### Success Criteria

#### Automated Verification

- `./mvnw test` passes (full suite — final confirmation)

#### Manual Verification

- `test-plan.md §6.1` and `§6.2` contain actionable guidance (not `TBD`)
- `change.md` status reads `implemented`
- `test-plan.md §3` Phase 1 row reads `complete`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Testing Strategy

### Unit Tests

- Compound edge case: 31→Feb-28 + `previousBusinessDay()` in `NextDueDateComputerTest`
- Idempotency under failure: two-run scenario with mocked side effect in `NotificationServiceTest`

### Integration Tests

- Full daily pipeline: obligation due tomorrow → send + SENT log (real DB)
- Date filter: obligation far out → no send (real DB)
- Retry path: FAILED log → re-sent → SENT updated (real DB)

### Manual Testing Steps

1. Run `./mvnw test` — all tests green
2. Inspect Phase 3 test: confirm `verify(mailSender, times(1))` (not `times(2)`) is the key assertion
3. Inspect Phase 4 Test 1: confirm `NotificationLog` row has `status=SENT` in the assertion

## Migration Notes

No DB migration required. `PENDING` is a new Java enum value; the column is `VARCHAR(20)` and accepts any value. Existing rows with `SENT` or `FAILED` are unaffected.

## References

- Research: `context/changes/testing-notification-pipeline-reliability/research.md`
- Test infrastructure: `src/test/java/com/example/finance_hq/TestcontainersConfiguration.java`
- Existing unit tests: `notification/NotificationServiceTest.java`, `notification/BusinessDayCalculatorTest.java`, `obligation/NextDueDateComputerTest.java`
- Existing integration test pattern: `notification/NotificationLogRepositoryTest.java`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Unit — Compound Date Edge Case

#### Automated

- [x] 1.1 `./mvnw test -Dtest=NextDueDateComputerTest` passes with new compound test — cf249a9

#### Manual

- [x] 1.2 New test name in output expresses the Feb-28-Saturday edge case clearly — cf249a9

### Phase 2: Code Fix — Idempotency for Risk #6

#### Automated

- [x] 2.1 `./mvnw test -Dtest=NotificationServiceTest` passes (all 7 existing tests) — e2295a4
- [x] 2.2 `./mvnw test -Dtest=NotificationPersistenceServiceTest` passes (updated stubs) — e2295a4
- [x] 2.3 `./mvnw test` passes (full suite) — e2295a4

#### Manual

- [x] 2.4 `recordPending()` uses `saveAndFlush()` not `save()` — e2295a4
- [x] 2.5 `runDailyNotifications()` catches `DataIntegrityViolationException` from `recordPending()` only — e2295a4

### Phase 3: Hermetic Test — Risk #6

#### Automated

- [x] 3.1 `./mvnw test -Dtest=NotificationServiceTest` passes including new idempotency test — 0589ab1

#### Manual

- [x] 3.2 `doAnswer` side-effect correctly simulates PENDING row written to mocked DB — 0589ab1
- [x] 3.3 `verify(mailSender, times(1))` is the load-bearing assertion (not `times(2)`) — 0589ab1

### Phase 4: Integration Tests — Risk #1 + Retry Path

#### Automated

- [x] 4.1 `./mvnw test -Dtest=NotificationServiceIntegrationTest` passes all three tests
- [x] 4.2 `./mvnw test` passes (full suite, no regressions)

#### Manual

- [x] 4.3 `@Transactional` causes each test to roll back — no residual rows between tests
- [x] 4.4 `@MockBean JavaMailSender` present — no real SMTP call made
- [x] 4.5 Test 1 assertion queries by `status=SENT`, not just count

### Phase 5: Cookbook Update

#### Automated

- [ ] 5.1 `./mvnw test` passes (final full suite)

#### Manual

- [ ] 5.2 `test-plan.md §6.1` and `§6.2` contain actionable guidance (not TBD)
- [ ] 5.3 `change.md` status reads `implemented`
- [ ] 5.4 `test-plan.md §3` Phase 1 row reads `complete`
