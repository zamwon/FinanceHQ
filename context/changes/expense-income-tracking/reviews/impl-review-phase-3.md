<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Expense and Income Tracking

- **Plan**: context/changes/expense-income-tracking/plan.md
- **Scope**: Phase 3 of 6
- **Date**: 2026-06-10
- **Verdict**: NEEDS ATTENTION (resolved during triage)
- **Findings**: 0 critical  3 warnings  3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Off-by-one in paid-cycle guard suppresses a valid notification

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/notification/NotificationService.java:44–46
- **Detail**: When `lastPaidDate` equals the prior due date boundary (e.g. paid on May 3 when nextDueDate is June 3), `isBefore` (exclusive) suppresses the June 3 notification even though the user only paid the previous cycle.
- **Fix**: Changed `isBefore(t.nextDueDate().minusMonths(1))` to `!t.obligation().getLastPaidDate().isAfter(t.nextDueDate().minusMonths(1))` so the boundary date is correctly treated as "not yet in the current cycle."
- **Decision**: FIXED

### F2 — Inline path suffix "/pay" repeated four times in test

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/example/finance_hq/obligation/ObligationPayIntegrationTest.java:78,96,118,140
- **Detail**: Lessons rule "inline path literals are prohibited" violated. `API_OBLIGATIONS + "/" + obligationId + "/pay"` repeated four times.
- **Fix**: Added `public static final String PAY_PATH = "/pay"` to `ObligationController`, used it in `@PostMapping("/{id}" + PAY_PATH)`, and extracted `private String payUrl(String id)` helper in the test referencing `ObligationController.PAY_PATH`.
- **Decision**: FIXED

### F3 — @Lazy on TransactionService injection is unnecessary

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/example/finance_hq/obligation/ObligationService.java:28
- **Detail**: No circular dependency exists in the bean graph (`TransactionService` does not inject `ObligationService`). `@Lazy` misleads future readers and left an unused import.
- **Fix**: Removed `@Lazy` from the constructor parameter and removed the unused `import org.springframework.context.annotation.Lazy`.
- **Decision**: FIXED

### F4 — FAILED notification_log rows suppress all future retries

- **Severity**: 💬 OBSERVATION
- **Impact**: 🔎 MEDIUM — pre-existing code not introduced by phase 3
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/notification/NotificationLogRepository.java:17
- **Detail**: `findAlreadyLoggedObligationIds` had no status filter. A FAILED log row for an obligation+dueDate pair blocked the daily run from creating a new attempt, preventing the retry job from taking over.
- **Fix**: Added `AND nl.status != 'FAILED'` to the JPQL query and updated the comment.
- **Decision**: FIXED

### F5 — markPaid's implicit contract with TransactionService.create is undocumented

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — documentation only
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/obligation/ObligationService.java:86
- **Detail**: `markPaid` builds a `CreateTransactionRequest` with several null fields, relying on `date` being `@NotNull` in `MarkObligationPaidRequest` to pass validation. Fragile if `TransactionService.create` ever tightens its checks.
- **Fix**: Added inline comment explaining which fields are intentionally null and why validation still passes.
- **Decision**: FIXED

### F6 — SchedulerTarget record sits between public and private methods

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — minor readability concern
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/example/finance_hq/obligation/ObligationService.java
- **Detail**: The `SchedulerTarget` record was declared between `markPaid` and the private `nextDueDate` helper, interrupting the public API block. Note: the rule targets methods, not type declarations, so this is a style observation rather than a strict violation.
- **Fix**: Moved the record declaration to just after the constructor, before the first public method.
- **Decision**: FIXED
