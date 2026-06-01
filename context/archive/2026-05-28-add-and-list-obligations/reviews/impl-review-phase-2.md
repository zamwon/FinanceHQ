<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Add and List Obligations

- **Plan**: context/changes/add-and-list-obligations/plan.md
- **Scope**: Phase 2 of 5
- **Date**: 2026-05-29
- **Verdict**: APPROVED
- **Findings**: 0 critical, 2 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — findAll missing @Transactional(readOnly = true)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: ObligationService.java:23
- **Detail**: `findAll` ran without a transaction. `Obligation.user` is `FetchType.LAZY` — touching the proxy outside a Hibernate session throws LazyInitializationException in future callers. Also `LocalDate.now()` was called once per obligation inside the stream, giving inconsistent "today" values across a midnight boundary.
- **Fix**: Added `@Transactional(readOnly = true)` to `findAll` and captured `LocalDate today = LocalDate.now()` once before the stream.
- **Decision**: FIXED

### F2 — FIXED_TERM creation accepts endDate in the past

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: ObligationService.java:31-34
- **Detail**: The FIXED_TERM guard checked `endDate != null` but not that it is in the future. A creation with `endDate = yesterday` would pass validation, persist, and immediately produce `nextDueDate = null`.
- **Fix**: Added `if (!req.endDate().isAfter(LocalDate.now())) throw new InvalidObligationException("FIXED_TERM endDate must be in the future")` inside the FIXED_TERM block.
- **Decision**: FIXED

### F3 — Exception handlers deferred to Phase 3 (planned gap)

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — no action needed now
- **Dimension**: Plan Adherence (informational)
- **Location**: GlobalExceptionHandler.java (not yet updated)
- **Detail**: `ObligationNotFoundException` and `InvalidObligationException` have no `@ExceptionHandler` entries yet. Expected Phase 2 state — plan explicitly places GlobalExceptionHandler changes in Phase 3 step 5. No controller exists yet to trigger these paths.
- **Decision**: SKIPPED

### F4 — No-op PATCH: update() accepted empty UpdateObligationRequest

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: ObligationService.java:47-48
- **Detail**: Both fields of `UpdateObligationRequest` are nullable. `{}` POST succeeded — Hibernate dirty-checking saved it as a no-op but client received 200 with unchanged data.
- **Fix**: Added early guard: if both `req.amount()` and `req.paymentDay()` are null, throw `InvalidObligationException("At least one field must be provided for update")`.
- **Decision**: FIXED

### F5 — NextDueDateComputer silent RECURRING/endDate behavior

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: NextDueDateComputer.java:11,20
- **Detail**: A RECURRING obligation with a non-null `endDate` silently ignores it (schema permits this). The two FIXED_TERM guards were hard to reason about without a comment.
- **Fix**: Added a 3-line comment block above `compute()` explaining the RECURRING/FIXED_TERM guard design.
- **Decision**: FIXED
