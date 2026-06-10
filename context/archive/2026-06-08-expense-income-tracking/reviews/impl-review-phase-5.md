<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Expense and Income Tracking

- **Plan**: context/changes/expense-income-tracking/plan.md
- **Scope**: Phase 5 of 6
- **Date**: 2026-06-10
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical  3 warnings  3 observations

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

### F1 — YEAR/MONTH functions are Hibernate extensions, not standard JPQL

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/dashboard/DashboardRepository.java (sumByTypeAndMonth and categoryBreakdown queries)
- **Detail**: YEAR() and MONTH() are Hibernate dialect extensions, not standard JPQL. PostgreSQL supports them today, but they're fragile across Hibernate upgrades. The trendData query in the same file already used the safe date-range predicate pattern.
- **Fix A ⭐ Applied**: Replaced YEAR/MONTH with date-range params (startDate/endDate) in both summary queries to match trendData.
  - Strength: Consistent pattern across all three queries; portable; already proven by trendData.
  - Tradeoff: DashboardService must compute startDate/endDate — one line per call site.
  - Confidence: HIGH.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A

### F2 — Malformed month param causes HTTP 500 instead of 400

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/dashboard/DashboardController.java:30
- **Detail**: YearMonth.parse(month) throws DateTimeParseException if the caller passes a malformed month string. GlobalExceptionHandler had no handler for this, so catch-all returned 500 instead of 400.
- **Fix**: Added @ExceptionHandler(DateTimeParseException.class) to GlobalExceptionHandler returning 400 ProblemDetail "Bad Request".
- **Decision**: FIXED

### F3 — Unbounded `months` param allows uncapped resource consumption

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/finance_hq/dashboard/DashboardController.java:34
- **Detail**: GET /api/dashboard/trends?months=N had no upper-bound guard. A caller could pass ?months=100000, causing unbounded DB query range and 100,000-slot LinkedHashMap allocation.
- **Fix**: Added guard in DashboardService.getMonthlyTrend: throws InvalidTransactionException if months < 1 or months > 120.
- **Decision**: FIXED (upper bound 120 — async path for longer windows deferred)

### F4 — Three uncommitted changes to phase 2 files sitting in working tree

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: GlobalExceptionHandler.java, TransactionService.java, TransactionControllerIntegrationTest.java (also NotificationLogRepository.java, NotificationService.java, ObligationController.java, ObligationService.java, ObligationPayIntegrationTest.java)
- **Detail**: Multiple modified files from phases 2 and 3 were unstaged/uncommitted. All were correct improvements but invisible in git history.
- **Fix**: Committed as three separate fix commits grouped by domain (obligation, transaction, dashboard).
- **Decision**: FIXED

### F5 — Dashboard integration test missing cross-user isolation case

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/example/finance_hq/dashboard/DashboardControllerIntegrationTest.java
- **Detail**: No test confirmed that user B's transactions don't leak into user A's dashboard summary. User-scoping enforced at query level but unverified end-to-end.
- **Fix**: Added summary_200_doesNotShowOtherUsersTransactions — seeds transactions for user A, calls summary as user B, asserts zeros.
- **Decision**: FIXED

### F6 — Test method summary_400_noToken asserts 401, not 400

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/example/finance_hq/dashboard/DashboardControllerIntegrationTest.java
- **Detail**: Test was named summary_400_noToken but asserted HTTP 401. 401 is correct; the name was misleading.
- **Fix**: Renamed to summary_401_noToken.
- **Decision**: FIXED
