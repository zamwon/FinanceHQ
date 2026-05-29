<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Add and List Obligations

- **Plan**: context/changes/add-and-list-obligations/plan.md
- **Scope**: All phases (1–5)
- **Date**: 2026-05-29
- **Verdict**: APPROVED (all findings resolved during triage)
- **Findings**: 0 critical  4 warnings  4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS (fixed) |
| Architecture | PASS |
| Pattern Consistency | PASS (fixed) |
| Success Criteria | PASS (47/47 tests) |

## Findings

### F1 — Exception message echoed verbatim in ProblemDetail detail field

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: GlobalExceptionHandler.java:64–75
- **Detail**: Both new handlers passed ex.getMessage() directly into ProblemDetail. Current messages were hardcoded strings so nothing leaked, but the pattern was fragile for future callers passing internal state.
- **Fix**: Hard-coded static strings in both handlers ("Obligation not found", "Invalid obligation request").
- **Decision**: FIXED via Fix A

### F2 — No DB-level CHECK on remaining_payments > 0

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: V4__create_obligations_table.sql:9-10
- **Detail**: Composite constraint enforced NOT NULL for FIXED_TERM but not positivity. Zero/negative values could reach the DB via direct writes.
- **Fix**: Added V5__add_remaining_payments_check.sql with CHECK (remaining_payments IS NULL OR remaining_payments > 0).
- **Decision**: FIXED

### F3 — TOCTOU race on concurrent delete / update

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; acceptable MVP risk
- **Dimension**: Safety & Quality
- **Location**: ObligationService.java:50–66
- **Detail**: Both update() and delete() already had @Transactional — finding was a false positive (agent didn't check). No action needed.
- **Decision**: FALSE POSITIVE — already correctly implemented

### F4 — No pagination on findAll

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — approved scope decision in plan
- **Dimension**: Safety & Quality
- **Location**: ObligationService.java:24–29
- **Detail**: No pagination per plan ("single-user, small data"). Added a default PageRequest cap of 200 via Page<Obligation> in repository; API contract unchanged.
- **Fix**: ObligationRepository now uses findAllByUser(User, Pageable); service uses PageRequest.of(0, 200, Sort.DESC createdAt).
- **Decision**: FIXED

### F5 — Mixed error response envelopes (Map vs ProblemDetail)

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🔎 MEDIUM — worth deciding before more APIs ship
- **Dimension**: Pattern Consistency
- **Location**: GlobalExceptionHandler.java
- **Detail**: Auth handlers returned Map<String,String>; obligation handlers returned ProblemDetail. Frontend only reads err.status so migration was safe.
- **Fix**: Migrated all GlobalExceptionHandler methods to return ProblemDetail. Frontend unaffected (only reads HTTP status).
- **Decision**: FIXED

### F6 — GET /api/obligations read-isolation not tested

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick fix; one test to add
- **Dimension**: Pattern Consistency
- **Location**: ObligationControllerIntegrationTest.java
- **Detail**: Ownership tests covered PATCH/DELETE but not GET. User B's list could theoretically return user A's data.
- **Fix**: Added test list_200_doesNotReturnOtherUsersObligations(). Total tests: 47/47.
- **Decision**: FIXED

### F7 — name maxLength mismatch: frontend 100 vs backend 255

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — one-liner
- **Dimension**: Pattern Consistency
- **Location**: obligation-dialog.component.ts:23
- **Detail**: Validators.maxLength(100) vs @Size(max=255) backend and VARCHAR(255) DB.
- **Fix**: Changed to Validators.maxLength(255).
- **Decision**: FIXED

### F8 — loading signal not cleared on success path

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — one-liner
- **Dimension**: Safety & Quality
- **Location**: obligation-dialog.component.ts:89-92
- **Detail**: next callback only emitted saved; loading signal not reset. Error path correctly reset it.
- **Fix**: Added this.loading.set(false) before this.saved.emit() in next callback.
- **Decision**: FIXED
