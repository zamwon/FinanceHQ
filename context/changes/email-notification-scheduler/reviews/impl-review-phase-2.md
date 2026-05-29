<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Email Notification Scheduler

- **Plan**: context/changes/email-notification-scheduler/plan.md
- **Scope**: Phase 2 of 4
- **Date**: 2026-05-29
- **Verdict**: NEEDS ATTENTION → FIXED
- **Findings**: 1 critical  2 warnings  2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | FAIL |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — decrementIfFixedTerm mutates a detached entity — decrement is silently lost

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: NotificationService.java:139–145
- **Detail**: findAllSchedulerTargets() commits its @Transactional(readOnly=true) before recordSuccess/markRetrySuccess open their own new @Transactional. Obligations are detached — setRemainingPayments mutates in-memory only, never persisted.
- **Fix Applied**: Fix B — injected ObligationRepository; added obligationRepository.save(obligation) inside decrementIfFixedTerm.
- **Decision**: FIXED via Fix B

### F2 — Daily job groups all targets by first target's dueDate

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: NotificationService.java:55
- **Detail**: groupBy(User) then dueDate = get(0).nextDueDate() misrepresents obligations with different due dates for the same user on the same notification day.
- **Fix Applied**: Changed groupBy to (userId + ":" + dueDate), mirroring the retry path.
- **Decision**: FIXED

### F3 — recordSuccess / recordFailure / markRetrySuccess are package-private, not private

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: NotificationService.java:110, 121, 129
- **Detail**: Plan specifies private. No access modifier = package-private (Java default).
- **Fix Applied**: Added `private` modifier to all three methods.
- **Decision**: FIXED

### F4 — NotificationLog JPA mapping is looser than DB schema

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: NotificationLog.java:21–32
- **Detail**: @ManyToOne had no optional=false; @JoinColumn had no nullable=false; due_date and status columns had no nullable=false.
- **Fix Applied**: Added optional=false to @ManyToOne and nullable=false to the three @Column annotations.
- **Decision**: FIXED

### F5 — FAILED rows retried indefinitely — no retry cap

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: NotificationService.java:66–85
- **Detail**: Hourly retry has no ceiling. Accepted for v0.1 single-user tool.
- **Decision**: ACCEPTED-AS-RULE: Notification retry loops should have a bounded retry policy
