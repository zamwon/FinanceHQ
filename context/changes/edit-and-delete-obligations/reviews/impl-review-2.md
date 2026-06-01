<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Edit and Delete Obligations (Round 2)

- **Plan**: context/changes/edit-and-delete-obligations/plan.md
- **Scope**: All Phases (1–3)
- **Date**: 2026-06-01
- **Verdict**: NEEDS ATTENTION (all findings fixed/skipped)
- **Findings**: 0 critical  2 warnings  2 observations

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

### F1 — Missing takeUntilDestroyed on submit() subscription

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: obligation-dialog.component.ts:97
- **Detail**: submit() subscribed to HTTP call without takeUntilDestroyed. Dialog mounts/unmounts on every add/edit — dangling subscriptions accumulate on destroy-mid-flight. DeleteDialogComponent already used the pattern; imports were already present.
- **Fix**: Added .pipe(takeUntilDestroyed(this.destroyRef)) to call.subscribe(...) in submit().
- **Decision**: FIXED

### F2 — Missing standalone: true in ObligationDialogComponent

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: obligation-dialog.component.ts
- **Detail**: DeleteDialogComponent and ToastComponent both declare standalone: true explicitly. ObligationDialogComponent did not. Works at runtime (Angular 17+ default), but inconsistent within the same feature folder.
- **Fix**: Added standalone: true to @Component decorator.
- **Decision**: FIXED

### F3 — Implicit disabled-fields + form.invalid interaction

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: obligation-dialog.component.ts:53–54
- **Detail**: Submit button guard [disabled]="loading() || form.invalid" silently relies on Angular's rule that disabled controls don't contribute to form.invalid. Works correctly but fragile — re-enabling a field without noticing this would allow invalid submissions through.
- **Fix**: Added a one-line comment beside the disable() block noting the behaviour.
- **Decision**: FIXED

### F4 — Toast fires unconditionally even if load() fails

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: obligations.component.ts:55–66
- **Detail**: onSaved()/onDeleted() show the success toast before load() completes. If load() fails, user sees "Obligation added." alongside a stale list. Accepted v0.1 risk.
- **Fix**: No change needed for v0.1. Note for future iteration.
- **Decision**: SKIPPED
