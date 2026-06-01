<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Email Notification Scheduler

- **Plan**: context/changes/email-notification-scheduler/plan.md
- **Scope**: All Phases (1–4)
- **Date**: 2026-06-01
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical  2 warnings  4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Unplanned NotificationPersistenceService extraction

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/java/com/example/finance_hq/notification/NotificationPersistenceService.java
- **Detail**: The plan specified recordSuccess, recordFailure, and markRetrySuccess as @Transactional private methods on NotificationService. The implementation extracted them into a dedicated @Service bean. The @Transactional isolation invariant is correctly honoured — NotificationService is non-transactional; NotificationPersistenceService handles all DB writes. The split is architecturally justified.
- **Fix A ⭐ Recommended**: Document as plan addendum — one sentence noting that persistence was extracted to NotificationPersistenceService to cleanly isolate @Transactional boundaries.
  - Strength: Updates the plan as the source of truth; costs 2 minutes.
  - Tradeoff: Plan becomes a slightly moving target; minimal.
  - Confidence: HIGH — this repo uses plan addenda for exactly this.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A (plan addendum added)

### F2 — LocalDateTime.now() without explicit timezone in entity timestamps

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/notification/NotificationLog.java:43, src/main/java/com/example/finance_hq/notification/NotificationPersistenceService.java:27
- **Detail**: NotificationLog constructor sets createdAt = LocalDateTime.now(). NotificationPersistenceService sets sentAt = LocalDateTime.now(). Railway containers default to UTC; Warsaw is UTC+1/+2. These audit timestamps will be stored 1–2 hours off from what a Warsaw user expects. The scheduler correctly uses ZoneId.of("Europe/Warsaw") for date logic but the entity timestamps don't apply the same discipline. Note: project-wide pattern in Obligation.java and RefreshToken too.
- **Fix A ⭐ Recommended**: Replace LocalDateTime.now() with LocalDateTime.now(ZoneId.of("Europe/Warsaw")) in the two locations.
  - Strength: Consistent with scheduler's timezone discipline; sentAt will match user's clock in the audit log.
  - Tradeoff: Only fixes these two callsites; Obligation and RefreshToken remain inconsistent.
  - Confidence: HIGH — pattern already established in NotificationScheduler.
  - Blind spot: None significant for these specific files.
- **Fix B**: Leave as-is and document as known limitation.
  - Strength: Zero code change; consistent with existing entity behavior.
  - Tradeoff: sentAt stored in UTC will confuse any future audit query comparing to Warsaw business hours.
  - Confidence: MEDIUM — acceptable for v0.1 single-user tool.
  - Blind spot: Whether Railway containers are actually UTC by default (likely but not verified).
- **Decision**: FIXED via Fix A (LocalDateTime.now(ZoneId.of("Europe/Warsaw")) applied in NotificationLog and NotificationPersistenceService)

### F3 — @PrePersist vs constructor for createdAt inconsistent with siblings

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/example/finance_hq/notification/NotificationLog.java:43
- **Detail**: NotificationLog sets createdAt in the constructor. Sibling RefreshToken uses @PrePersist for the same purpose, which is the standard JPA pattern. Functionally equivalent but a future maintainer may be confused by the divergence.
- **Fix**: Migrate createdAt initialization to @PrePersist, matching RefreshToken.
- **Decision**: FIXED (createdAt moved to @PrePersist with Warsaw timezone)

### F4 — N+1 existsByObligationIdAndDueDate calls in the hot path

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/notification/NotificationService.java:45-49
- **Detail**: For each obligation passing the date filter, a separate SELECT EXISTS query is issued. For a single-user personal tool this is negligible per the plan's own performance note. Flagged for awareness if user base grows.
- **Fix**: No action needed now. Track as tech-debt note when user base grows.
- **Decision**: FIXED (replaced N+1 existsByObligationIdAndDueDate with batch findAlreadyLoggedObligationIds; tests updated)

### F5 — Implicit double-decrement guard relies on UNIQUE constraint, undocumented

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/notification/NotificationPersistenceService.java (markRetrySuccess)
- **Detail**: markRetrySuccess decrements remainingPayments without checking whether the log row was previously FAILED. The guard against double-decrement is the UNIQUE(obligation_id, due_date) constraint — if daily job already wrote SENT, findByStatusWithObligationAndUser(FAILED) cannot return it. Safety property is correct but implicit.
- **Fix**: Add one-line comment above markRetrySuccess: "Only called for FAILED rows; UNIQUE(obligation_id, due_date) prevents double-decrement."
- **Decision**: FIXED (comment added)

### F6 — FIXED_TERM remainingPayments decrement lacks a dedicated unit test

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Success Criteria
- **Location**: src/test/java/com/example/finance_hq/notification/NotificationServiceTest.java:119-131
- **Detail**: fixedTermObligationTriggersSendAndRecordsSuccess verifies that persistenceService.recordSuccess() is called — but since NotificationPersistenceService is mocked, the actual decrement is never exercised. NotificationPersistenceService has no unit test of its own. The FIXED_TERM decrement path is untested at any granularity.
- **Fix**: Add NotificationPersistenceServiceTest with one test: given FIXED_TERM obligation with remainingPayments=3, recordSuccess() saves SENT log and saves obligation with remainingPayments=2.
- **Decision**: FIXED (NotificationPersistenceServiceTest added with decrement + no-decrement-for-recurring tests)
