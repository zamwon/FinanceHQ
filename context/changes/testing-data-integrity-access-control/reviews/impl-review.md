<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Data Integrity and Access Control

- **Plan**: context/changes/testing-data-integrity-access-control/plan.md
- **Scope**: All phases (1–3)
- **Date**: 2026-06-03
- **Verdict**: APPROVED
- **Findings**: 0 critical · 1 warning · 4 observations (1 dismissed as false positive)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS (F1 dismissed — delete_404_notFound was pre-existing) |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS (all observations fixed) |
| Success Criteria | PASS |

## Findings

### F1 — Unplanned delete_404_notFound test

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: ObligationControllerIntegrationTest.java:361–367
- **Detail**: Review agent flagged delete_404_notFound as unplanned. Plan research section explicitly calls it "the existing test" and the structural twin of the planned update_404_notFound — it was pre-existing before this change. False positive.
- **Decision**: DISMISSED (false positive — pre-existing test)

### F2 — deleteById internal JPA path broken by findById override

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: ObligationRepository.java:15–19
- **Detail**: Spring Data's deleteById() delegates internally through findById(). The override will throw UnsupportedOperationException if deleteById() is ever called. Added a two-line warning comment above the override documenting this constraint.
- **Fix**: Added warning comment noting deleteById() is also broken; use repository.delete(entity) instead.
- **Decision**: FIXED

### F3 — findById override is unique; no Javadoc rationale on the interface

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: ObligationRepository.java:13
- **Detail**: No other repository overrides an inherited JpaRepository method to throw. Without a top-level comment, the pattern is opaque to future contributors.
- **Fix**: Added interface-level Javadoc explaining the ownership invariant and why findById() is intentionally disabled.
- **Decision**: FIXED

### F4 — USE_BIG_DECIMAL_FOR_FLOATS diverges silently from sibling class

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: ObligationDataIntegrityTest.java:52–53
- **Detail**: Custom ObjectMapper config differs from sibling class. BigDecimal casts at lines 83 and 128 depend on this silently. A future cleanup removing the flag would produce a ClassCastException at runtime.
- **Fix**: Added inline comment explaining USE_BIG_DECIMAL_FOR_FLOATS is required for BigDecimal cast assertions.
- **Decision**: FIXED

### F5 — Repository-level createdAt assertion weaker than intended

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: ObligationDataIntegrityTest.java:130–132
- **Detail**: isNotNull() adds no signal beyond the API assertion already at line 129. Plan intent was to prove the DB column was not overwritten.
- **Fix**: Replaced isNotNull() with isEqualToIgnoringNanos(LocalDateTime.parse((String) before.get("createdAt"))) — proves DB value matches pre-PATCH API value.
- **Decision**: FIXED
