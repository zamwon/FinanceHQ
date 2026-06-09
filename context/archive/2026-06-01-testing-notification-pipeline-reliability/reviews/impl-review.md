<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Phase 1 — Test notification pipeline reliability

- **Plan**: context/changes/testing-notification-pipeline-reliability/plan.md
- **Scope**: All phases (Phase 1–5)
- **Date**: 2026-06-01
- **Verdict**: APPROVED (after fixes)
- **Findings**: 0 critical, 4 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS (after fixes) |
| Architecture | PASS |
| Pattern Consistency | PASS (after fixes) |
| Success Criteria | PASS |

## Findings

### F1 — Missing log.warn when PENDING row not found in recordSuccess/recordFailure

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: NotificationPersistenceService.java:46,61
- **Detail**: `ifPresent` silently no-ops when the PENDING row is missing. Without a warn log, a missing-PENDING scenario (e.g. concurrent delete, bug) produces no observable signal.
- **Fix**: Replace `ifPresent` with `ifPresentOrElse` adding `log.warn` for missing-row branch in both `recordSuccess()` and `recordFailure()`.
- **Decision**: FIXED

### F2 — recordSuccess failure leaves obligation stranded in PENDING forever

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: NotificationService.java — runDailyNotifications()
- **Detail**: If email sends but `recordSuccess()` throws (DB write fails), the PENDING row is never updated to SENT or FAILED. The retry scheduler only queries FAILED rows, so the obligation is silently stranded.
- **Fix B ⭐ Applied**: Catch `RuntimeException` from `recordSuccess()` and call `recordFailure()` so the obligation lands in FAILED and is retried. Updated `doesNotResendWhenPendingRowExistsFromPriorFailedRun` test to reflect that the exception is now caught internally.
- **Decision**: FIXED

### F3 — markRetrySuccess failure aborts remaining retry groups

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: NotificationService.java:102 — retryFailedNotifications()
- **Detail**: If `markRetrySuccess()` throws a RuntimeException, the exception propagates out of the forEach lambda and aborts all remaining retry groups in that run.
- **Fix**: Wrap `markRetrySuccess()` in `catch(RuntimeException)` — log and continue to next group.
- **Decision**: FIXED

### F4 — Misleading test name doesNotResendWhenPendingRowExistsFromPriorFailedRun

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: NotificationServiceTest.java:156
- **Detail**: Name says "prior failed run" (implying status=FAILED) but the oracle is the PENDING row written by `recordPending()`. Status never reaches FAILED in this test.
- **Fix**: Renamed to `doesNotResendWhenRecordSuccessThrowsAndRunRepeated`.
- **Decision**: FIXED

### F5 — findAlreadyLoggedObligationIds has no status filter (intentional but undocumented)

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: NotificationLogRepository.java:16
- **Detail**: Query returns obligation IDs for ANY log row (PENDING, SENT, FAILED). This is correct design but a future reader adding a status filter would silently break deduplication.
- **Fix**: Added one-line comment: "No status filter: PENDING blocks same-run duplicate; SENT blocks re-send on subsequent runs."
- **Decision**: FIXED

### F6 — N+1 query pattern in recordSuccess/recordFailure (observation only)

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: NotificationPersistenceService.java:43,59
- **Detail**: `recordSuccess()` and `recordFailure()` each issue one `findByObligationIdAndDueDate` query per target inside a loop. For the typical case (few obligations per user per due date) this is acceptable; at scale, a single `findByObligationIdAndDueDateIn` bulk query would be more efficient.
- **Fix**: Not applied — N+1 is acceptable at current scale. Re-evaluate if obligation counts per group grow significantly.
- **Decision**: SKIPPED (acceptable at MVP scale)
