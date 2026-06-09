<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: UUID Entity IDs

- **Plan**: context/changes/uuid-entity-ids/plan.md
- **Scope**: All Phases (Phase 1 + Phase 2 of 2)
- **Date**: 2026-05-28
- **Verdict**: APPROVED (after triage fixes)
- **Findings**: 0 critical, 4 warnings, 3 observations

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

### F1 — SecurityConfig and GlobalExceptionHandler bundled into this change

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: SecurityConfig.java, GlobalExceptionHandler.java
- **Detail**: Both files changed to fix pre-existing SpaForwardingConfigTest failures, committed in the uuid-entity-ids phase commit. Changes are correct and safe but unrelated to UUID migration.
- **Fix**: Accept as-is — fixes are good and tests pass.
- **Decision**: ACCEPTED

### F2 — GlobalExceptionHandler catch-all swallows stack traces silently

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: GlobalExceptionHandler.java:45
- **Detail**: @ExceptionHandler(Exception.class) returns 500 but logs nothing server-side.
- **Fix**: Added `log.error("Unexpected error", ex)` with SLF4J logger field.
- **Decision**: FIXED

### F3 — User.createdAt missing nullable = false on @Column

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: User.java
- **Detail**: Obligation correctly marks `nullable = false`; User did not.
- **Fix**: Added `nullable = false` to `@Column(name = "created_at")` on User.createdAt.
- **Decision**: FIXED

### F4 — Inconsistent createdAt initialization across entities

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: User.java, RefreshToken.java vs. Obligation.java
- **Detail**: Obligation uses @PrePersist; User and RefreshToken set createdAt only in constructor.
- **Fix**: Added `@PrePersist` hook to User and RefreshToken matching Obligation's pattern.
- **Decision**: FIXED

### F5 — No CHECK constraint for FIXED_TERM requiring end_date/remaining_payments

- **Severity**: OBSERVATION
- **Impact**: 🔎 MEDIUM — worth noting; belongs in the obligations phase plan
- **Dimension**: Safety & Quality
- **Location**: V4__create_obligations_table.sql
- **Detail**: Nothing prevented a FIXED_TERM obligation from having both end_date and remaining_payments as NULL.
- **Fix**: Added `CONSTRAINT chk_fixed_term_fields CHECK (period != 'FIXED_TERM' OR (end_date IS NOT NULL AND remaining_payments IS NOT NULL))`.
- **Decision**: FIXED

### F6 — payment_day 29–31 valid for all months; clamping must be documented

- **Severity**: OBSERVATION
- **Dimension**: Safety & Quality
- **Location**: V4__create_obligations_table.sql
- **Detail**: CHECK allows payment_day up to 31 for all months; scheduler must clamp. Accepted MVP simplification.
- **Decision**: SKIPPED

### F7 — RefreshTokenRepository.findByTokenForUpdate lacks @Transactional

- **Severity**: OBSERVATION
- **Dimension**: Reliability
- **Location**: RefreshTokenRepository.java:17
- **Detail**: Pessimistic lock relies on callers being in a transaction; no guard at repository level.
- **Fix**: Added `@Transactional` annotation to `findByTokenForUpdate`.
- **Decision**: FIXED
