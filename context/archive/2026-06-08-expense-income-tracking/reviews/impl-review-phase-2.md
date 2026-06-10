<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Expense and Income Tracking

- **Plan**: context/changes/expense-income-tracking/plan.md
- **Scope**: Phase 2 of 6
- **Date**: 2026-06-09
- **Verdict**: APPROVED (after triage fixes)
- **Findings**: 0 critical, 4 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS (fixed) |
| Architecture | PASS |
| Pattern Consistency | PASS (fixed) |
| Success Criteria | PASS |

## Findings

### F1 — RECURRING transaction silently stores with null paymentDay

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: TransactionService.java:44
- **Detail**: POST with period=RECURRING but no paymentDay passed all validation, persisted, and returned nextExpectedDate=null silently. DB CHECK constraint would eventually catch it but service should fail fast.
- **Fix**: Added guard `if (req.period() != null && req.paymentDay() == null)` before FIXED_TERM block.
- **Decision**: FIXED

### F2 — handleInvalidObligation uses fixed string; handleInvalidTransaction uses ex.getMessage()

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: GlobalExceptionHandler.java:93
- **Detail**: Inconsistency between obligation and transaction 400 handlers — obligation returned opaque "Invalid obligation request", transaction returned actual reason.
- **Fix**: Updated handleInvalidObligation to use ex.getMessage().
- **Decision**: FIXED

### F3 — Missing 401 auth-boundary tests

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: TransactionControllerIntegrationTest.java (missing)
- **Detail**: No list_401_noToken or create_401_noToken tests. Misconfigured security would go undetected.
- **Fix**: Added list_401_noToken and create_401_noToken tests.
- **Decision**: FIXED

### F4 — Missing cross-user read-isolation test

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: TransactionControllerIntegrationTest.java (missing)
- **Detail**: No list_200_doesNotReturnOtherUsersTransactions test to verify findAllByUser scoping.
- **Fix**: Added list_200_doesNotReturnOtherUsersTransactions test.
- **Decision**: FIXED

### F5 — Missing update_404_notFound and delete_404_notFound tests

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Pattern Consistency
- **Location**: TransactionControllerIntegrationTest.java (missing)
- **Detail**: Wrong-user 404 tests cover same code path; pure not-found tests exist in obligation suite.
- **Decision**: SKIPPED

### F6 — @RequestMapping path literal not extracted to constant in controller

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Lessons Compliance
- **Location**: TransactionController.java:16
- **Detail**: Pre-existing in ObligationController too — not a regression introduced by Phase 2.
- **Decision**: SKIPPED

### F7 — ex.getMessage() forwarded to client in handleInvalidTransaction

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Safety & Quality
- **Location**: GlobalExceptionHandler.java:107
- **Detail**: Current messages are safe. Ensure all throw sites pass only user-safe strings.
- **Decision**: SKIPPED
