<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Add and List Obligations

- **Plan**: context/changes/add-and-list-obligations/plan.md
- **Scope**: Phase 1 of 5
- **Date**: 2026-05-28
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical  3 warnings  2 observations

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

### F1 — No enum value CHECK constraints in SQL migration

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: V4__create_obligations_table.sql:6-7
- **Detail**: category and period columns accepted any string — no CHECK constraint. Hibernate throws on unmapped values at read time.
- **Fix**: Add CHECK constraints for both enum columns.
- **Decision**: FIXED — CHECK constraints added to V4.

### F2 — findById IDOR risk on ObligationRepository

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: ObligationRepository.java
- **Detail**: JpaRepository's inherited findById bypasses ownership check. IDs are sequential BIGSERIAL integers (guessable).
- **Fix A ⭐**: Override findById to throw UnsupportedOperationException.
- **Fix B**: Javadoc comment only.
- **Decision**: SKIPPED — accepted as minor risk for MVP single-user scope.

### F3 — @ManyToOne defaults to EAGER fetch

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: Obligation.java:18
- **Detail**: Eager fetch causes unnecessary User JOIN on every obligation load.
- **Fix**: Add fetch = FetchType.LAZY.
- **Decision**: FIXED — FetchType.LAZY added.

### F4 — createdAt set in constructor without @PrePersist guard

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: Obligation.java:48
- **Detail**: No-arg constructor leaves createdAt null; NOT NULL constraint rejects save.
- **Fix**: Add @PrePersist void prePersist().
- **Decision**: FIXED — @PrePersist added.
