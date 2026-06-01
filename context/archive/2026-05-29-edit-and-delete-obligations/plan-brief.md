# Edit and Delete Obligations — Plan Brief

> Full plan: `context/changes/edit-and-delete-obligations/plan.md`

## What & Why

The edit and delete flows were already built during S-02 (backend endpoints, frontend dialogs, wired buttons). This change closes three real gaps the implementation left open — a misleading UX for FIXED_TERM edits, silent failures in the delete dialog, and missing backend test coverage — and adds a floating toast so users get explicit confirmation after each action.

## Starting Point

All CRUD endpoints are implemented and secured. `ObligationDialogComponent` and `DeleteDialogComponent` exist and are wired into the list view. The backend integration test suite has 17 cases covering happy paths and cross-user authorization but not update-validation 400s or delete-not-found 404.

## Desired End State

Editing a FIXED_TERM obligation shows `endDate` and `remainingPayments` as non-interactive (greyed out). A failed delete surfaces an error message inside the confirmation dialog rather than silently resetting. After any successful add, edit, or delete a floating top-right toast auto-dismisses after 3 s. Three new backend integration tests cover the missing validation edge cases.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| FIXED_TERM fields in edit mode | Disable (greyed-out) | Consistent with how name/category/period are already handled in the same dialog | Plan |
| Delete failure feedback location | Inline in dialog | Keeps context; user can retry or cancel without re-opening | Plan |
| Toast style | Floating top-right, auto-dismiss 3s | Standard pattern; no layout shift; simple signal-based implementation | Plan |
| Toast trigger for add | Included (not just edit/delete) | Inconsistent to toast edit/delete but not add | Plan |
| Post-action list reload | Keep existing `load()` call | Already works; adding optimistic updates would be out of scope | Plan |
| Missing backend tests | Add 3 cases | Service-level validation paths are reachable via HTTP but untested | Plan |

## Scope

**In scope:**
- Disable `endDate`/`remainingPayments` in edit mode of `ObligationDialogComponent`
- Fix validation copy bug ("max 100 chars" → "max 255 chars")
- Add `error` signal + UI to `DeleteDialogComponent`
- Add 3 integration tests: `update_400_bothFieldsNull`, `update_400_amountZero`, `delete_404_notFound`
- `ToastService` + `ToastComponent` mounted at app root
- Wire toasts into `ObligationsComponent` for add/edit/delete success

**Out of scope:**
- Toast animation / slide-in transition
- Toast queue (multiple simultaneous messages)
- Delete undo
- Editing any field beyond `amount` and `paymentDay` (per FR-005)

## Architecture / Approach

Minimal shared service: `ToastService` is `providedIn: 'root'`, holds a single `signal<string | null>`, and auto-clears after a configurable timeout. `ToastComponent` is a standalone outlet mounted once in `app.html` alongside `<router-outlet />`. `ObligationsComponent` injects the service directly and calls `show()` in its existing `onSaved()` and `onDeleted()` callbacks. No new routing, no new NgRx, no new HTTP calls.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Backend test coverage | 3 new integration tests close validation/404 gaps | None — purely additive |
| 2. Dialog fixes | FIXED_TERM disable + delete error UI + copy fix | Disabling endDate/remainingPayments must not invalidate the form in edit mode |
| 3. Toast system | ToastService + ToastComponent + wired in obligations | None — isolated shared utility |

**Prerequisites:** Backend running locally (`./mvnw spring-boot:run --spring.profiles.active=local`); Angular dev server (`ng serve` from `src/main/frontend/`).  
**Estimated effort:** ~1 session across 3 phases.

## Open Risks & Assumptions

- Disabling `endDate`/`remainingPayments` in edit mode does not trigger Angular form validation errors because disabled controls are excluded from validity checks — confirmed by Angular reactive forms contract.
- The `editing()` signal is still set when `onSaved()` fires (it's only reset on `openAdd()`), making it a safe add-vs-edit discriminator for the toast message.

## Success Criteria (Summary)

- All 20 backend integration tests pass (17 existing + 3 new).
- Editing a FIXED_TERM obligation shows read-only `endDate`/`remainingPayments`; saving triggers "Obligation updated." toast.
- A simulated delete failure shows an inline error message inside the confirmation dialog.
