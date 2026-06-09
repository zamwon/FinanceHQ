<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Obligation Category Edit

- **Plan**: `context/changes/obligation-category-edit/plan.md`
- **Scope**: All phases (1–2)
- **Date**: 2026-06-09
- **Verdict**: APPROVED (all findings fixed during triage)
- **Findings**: 0 critical  2 warnings  1 observation

## Verdicts

| Dimension | Verdict |
|---|---|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING → FIXED |
| Architecture | PASS |
| Pattern Consistency | WARNING → FIXED |
| Success Criteria | PASS |

## Findings

### F1 — Edit submit always sends all fields with non-null assertions

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `obligation.model.ts:15`
- **Detail**: `UpdateObligationDto` was typed as `Partial<Pick<...>>` (all optional) but the call site asserted all three fields as non-null. The type said "all optional" while the component always sends all three via required form validators.
- **Fix**: Changed type from `Partial<Pick<...>>` to `Pick<...>` — removed `Partial<>` wrapper so the type matches the call site.
- **Decision**: FIXED via Fix A

### F2 — No test for invalid category enum value

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency / Safety
- **Location**: `ObligationControllerIntegrationTest.java` (missing test)
- **Detail**: No negative test for `{"category":"GARBAGE"}` on the PATCH endpoint. Adding the test revealed that `GlobalExceptionHandler` did not handle `HttpMessageNotReadableException`, causing invalid enum values to return 500 instead of 400.
- **Fix**: Added `update_400_invalidCategory` test; added `handleMessageNotReadable(HttpMessageNotReadableException)` handler to `GlobalExceptionHandler` returning 400.
- **Decision**: FIXED

### F3 — Test name update_400_bothFieldsNull is stale

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `ObligationControllerIntegrationTest.java:334`
- **Detail**: DTO now has three nullable fields; test name said "both" implying only two.
- **Fix**: Renamed to `update_400_allFieldsNull`.
- **Decision**: FIXED
