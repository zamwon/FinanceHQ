# Edit and Delete Obligations Implementation Plan

## Overview

The core edit and delete flows (backend endpoints, frontend dialogs, routing) were implemented during S-02. This plan closes three real gaps that remain: a misleading edit UX for FIXED_TERM obligations, missing error feedback in the delete dialog, and absent backend integration tests for validation edge cases. It also adds a floating toast notification system so users get explicit confirmation after add, edit, and delete actions.

## Current State Analysis

All backend CRUD endpoints exist and are secured:
- `PATCH /api/obligations/{id}` — updates `amount` and `paymentDay` only; `ObligationService.update()` throws `InvalidObligationException` when both fields are null; `GlobalExceptionHandler` maps this to 400 and `ObligationNotFoundException` to 404.
- `DELETE /api/obligations/{id}` — ownership-checked via `findByIdAndUser`; 404 on miss.

Integration test suite (`ObligationControllerIntegrationTest`) covers 17 cases including cross-user 404 protection, but lacks three update/delete validation paths.

Frontend components are wired end-to-end:
- `ObligationDialogComponent` — serves add and edit modes; in edit mode it disables `name`, `category`, `period` but NOT `endDate` / `remainingPayments`, which remain editable while `submit()` silently ignores any changes to them.
- `DeleteDialogComponent` — shows confirmation modal; its error callback only resets `loading` with no error signal or message, leaving the user with no feedback on failure.
- Validation copy bug: name field error says "max 100 chars" but the validator is `maxLength(255)`.

No toast or success-notification infrastructure exists.

## Desired End State

After this plan:
- Editing a FIXED_TERM obligation shows `endDate` and `remainingPayments` as greyed-out read-only fields; the user can only change `amount` and `paymentDay`.
- A failed delete shows an inline error message inside the confirmation dialog.
- A floating top-right toast appears (and auto-dismisses after 3 s) after any successful add, edit, or delete.
- Three missing integration test cases fill the update-validation and delete-404 gaps.

### Key Discoveries

- `obligation-dialog.component.ts:48–50` — three fields are disabled in edit mode; `endDate`/`remainingPayments` are not.
- `delete-dialog.component.ts:20` — `error: () => this.loading.set(false)` — no error signal exists.
- `obligation-dialog.component.html:20` — copy says "max 100 chars"; validator is `maxLength(255)`.
- `obligations.component.ts:38–41` — `editing()` signal holds the obligation being edited, still set when `onSaved()` fires, making it a reliable add-vs-edit discriminator.
- `app.html:1` — single `<router-outlet />`, a clean insertion point for a toast outlet.

## What We're NOT Doing

- No edit of `name`, `category`, `period`, `endDate`, or `remainingPayments` — PRD FR-005 explicitly limits editable fields to amount and payment date.
- No toast queue or multiple simultaneous toasts — one active message at a time is sufficient for a single-user tool.
- No animated slide-in/out for the toast — static opacity is sufficient; animation can be added later.
- No delete undo/undo flow.

## Implementation Approach

Three self-contained phases. Phase 1 (backend tests) can be verified with the Maven test runner in isolation. Phases 2 and 3 are both frontend; they're kept separate because Phase 2 fixes existing components while Phase 3 adds new shared infrastructure.

---

## Phase 1: Backend — Integration test coverage

### Overview

Add three integration test cases to `ObligationControllerIntegrationTest` that exercise the service-level validation paths not yet reachable from the existing HTTP-layer tests.

### Changes Required

#### 1. Three new test methods

**File**: `src/test/java/com/example/finance_hq/obligation/ObligationControllerIntegrationTest.java`

**Intent**: Cover the three untested paths: PATCH with an empty body (both fields null → service throws `InvalidObligationException` → 400), PATCH with `amount=0` (bean validation on `UpdateObligationRequest` → 400), and DELETE of a random UUID that doesn't belong to the user (→ 404).

**Contract**: Each method follows the existing register-and-login → perform → andExpect pattern. Place the three methods in a new `// ── Additional update/delete validation ──` section between the ownership tests and the private helpers block.

- `update_400_bothFieldsNull` — creates an obligation, then PATCHes it with `{}` (empty JSON object); expects `status().isBadRequest()`.
- `update_400_amountZero` — creates an obligation, then PATCHes it with `{"amount": 0}`; expects `status().isBadRequest()`.
- `delete_404_notFound` — calls DELETE on `OBLIGATIONS_BASE + "/" + UUID.randomUUID()` with a valid token; expects `status().isNotFound()`.

### Success Criteria

#### Automated Verification

- All obligation integration tests pass: `./mvnw test -Dtest=ObligationControllerIntegrationTest`

#### Manual Verification

- No manual testing needed for backend-only test additions.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Phase 2: Frontend — Dialog fixes

### Overview

Fix three issues in the existing dialog components: disable `endDate`/`remainingPayments` in edit mode, surface a delete-failure error message, and correct the validation copy.

### Changes Required

#### 1. ObligationDialogComponent — disable FIXED_TERM fields in edit mode

**File**: `src/main/frontend/src/app/features/obligations/obligation-dialog/obligation-dialog.component.ts`

**Intent**: When editing an obligation, `endDate` and `remainingPayments` must be disabled alongside `name`, `category`, and `period`. Without this, a user editing a FIXED_TERM obligation sees those fields as interactive, can modify them, and receives no indication that the changes will be ignored.

**Contract**: Inside the `if (this.obligation)` branch in `ngOnInit` (after `this.form.controls.period.disable()`, line 50), add:
```typescript
this.form.controls.endDate.disable();
this.form.controls.remainingPayments.disable();
```

#### 2. ObligationDialogComponent template — fix validation copy

**File**: `src/main/frontend/src/app/features/obligations/obligation-dialog/obligation-dialog.component.html`

**Intent**: The name field validation error message misreports the maximum length.

**Contract**: Change the error text on line 20 from `Required (max 100 chars)` to `Required (max 255 chars)`.

#### 3. DeleteDialogComponent — add error signal

**File**: `src/main/frontend/src/app/features/obligations/delete-dialog/delete-dialog.component.ts`

**Intent**: When a delete HTTP call fails, the user must see a message rather than a silently re-enabled button.

**Contract**: Add `error = signal('')` as a class field. In `confirm()`'s error callback, replace `error: () => this.loading.set(false)` with:
```typescript
error: () => { this.loading.set(false); this.error.set('Failed to delete. Please try again.'); }
```

#### 4. DeleteDialogComponent template — render error message

**File**: `src/main/frontend/src/app/features/obligations/delete-dialog/delete-dialog.component.html`

**Intent**: Display the error signal when set, using the same red alert pattern as `obligation-dialog.component.html`.

**Contract**: Add an `@if (error())` block before the action `<div class="flex justify-end gap-2">`, rendering a `bg-red-50 dark:bg-red-950` alert div with the error text.

### Success Criteria

#### Automated Verification

- Angular build succeeds: `ng build` (run from `src/main/frontend/`)

#### Manual Verification

- Edit a FIXED_TERM obligation → `endDate` and `remainingPayments` fields render greyed-out and non-interactive, matching the disabled style of `name`/`category`/`period`.
- Edit a RECURRING obligation → only `amount` and `paymentDay` are editable (no FIXED_TERM fields shown — `isFixedTerm` returns false).
- Simulate a delete failure (DevTools → Network → block the DELETE request) → error message appears inside the dialog; Cancel button still works.
- Name field: trigger validation → error reads "Required (max 255 chars)".

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Phase 3: Toast notification system

### Overview

Build a minimal `ToastService` + `ToastComponent` and wire it into `ObligationsComponent` so a floating top-right notification confirms successful add, edit, and delete actions.

### Changes Required

#### 1. ToastService

**File**: `src/main/frontend/src/app/shared/ui/toast/toast.service.ts` *(new)*

**Intent**: Injectable singleton holding the active toast message as a signal. `show()` sets the text and schedules auto-clear.

**Contract**: `providedIn: 'root'`. Fields: `toast = signal<string | null>(null)`. Method `show(text: string, duration = 3000)`: sets `this.toast.set(text)`, then `setTimeout(() => this.toast.set(null), duration)`.

#### 2. ToastComponent class

**File**: `src/main/frontend/src/app/shared/ui/toast/toast.component.ts` *(new)*

**Intent**: Standalone component that renders the active toast message from `ToastService`. Injects the service directly — no inputs or outputs.

**Contract**: Standalone, `selector: 'app-toast'`, `templateUrl: './toast.component.html'`. Inject `ToastService` as `protected toastSvc`.

#### 3. ToastComponent template

**File**: `src/main/frontend/src/app/shared/ui/toast/toast.component.html` *(new)*

**Intent**: Fixed-position top-right card, visible only when `toastSvc.toast()` is non-null.

**Contract**:
```html
@if (toastSvc.toast()) {
  <div class="fixed top-4 right-4 z-50 bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-700 rounded-lg px-4 py-3 shadow-lg flex items-center gap-2 text-sm text-zinc-800 dark:text-zinc-200">
    <span class="text-green-600 dark:text-green-400 font-bold">✓</span>
    {{ toastSvc.toast() }}
  </div>
}
```

#### 4. App root — mount toast outlet

**File**: `src/main/frontend/src/app/app.html`

**Intent**: Mount `ToastComponent` at the application root so it overlays all routed views.

**Contract**: Add `<app-toast />` after `<router-outlet />`. Import `ToastComponent` in `app.ts` imports array.

#### 5. ObligationsComponent — trigger toasts on success

**File**: `src/main/frontend/src/app/features/obligations/obligations.component.ts`

**Intent**: Show a contextual message after each successful obligation mutation. The `editing()` signal is still set when `onSaved()` fires, so it reliably distinguishes add from edit.

**Contract**: Inject `ToastService`. Update `onSaved()`:
```typescript
onSaved(): void {
  const wasEdit = this.editing() !== null;
  this.showAddEdit.set(false);
  this.toast.show(wasEdit ? 'Obligation updated.' : 'Obligation added.');
  this.load();
}
```
Update `onDeleted()`:
```typescript
onDeleted(): void {
  this.showDelete.set(false);
  this.toast.show('Obligation deleted.');
  this.load();
}
```

### Success Criteria

#### Automated Verification

- Angular build succeeds: `ng build` (run from `src/main/frontend/`)

#### Manual Verification

- Add an obligation → toast "Obligation added." appears top-right, auto-dismisses after ~3 s.
- Edit an obligation → toast "Obligation updated." appears.
- Delete an obligation → toast "Obligation deleted." appears.
- Toast does not block clicking the "Add Obligation" button while visible.
- Dark mode: toast renders correctly (white card in dark theme).

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Testing Strategy

### Integration Tests (backend)

- `update_400_bothFieldsNull` — PATCH `{}` → 400
- `update_400_amountZero` — PATCH `{"amount":0}` → 400
- `delete_404_notFound` — DELETE random UUID → 404

### Manual Testing Steps

1. Log in with a test account that has at least one RECURRING and one FIXED_TERM obligation.
2. Edit the RECURRING obligation: verify only `amount` and `paymentDay` are editable; save and confirm toast appears.
3. Edit the FIXED_TERM obligation: verify `endDate` and `remainingPayments` are greyed-out; save and confirm toast.
4. Add a new obligation: confirm "Obligation added." toast.
5. Delete an obligation: confirm dialog shows the name; confirm delete → "Obligation deleted." toast; obligation removed from list.
6. With DevTools open, block the DELETE request, click Delete → error message inside the dialog; cancel → dialog closes cleanly.
7. Toggle dark mode; repeat steps 2 and 4 to verify toast renders in dark theme.

## References

- Related PRD: `context/foundation/prd.md` (FR-005, FR-006)
- Existing integration tests: `src/test/java/com/example/finance_hq/obligation/ObligationControllerIntegrationTest.java`
- Dialog components: `src/main/frontend/src/app/features/obligations/obligation-dialog/`, `delete-dialog/`
- App root: `src/main/frontend/src/app/app.ts`, `app.html`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Backend — Integration test coverage

#### Automated

- [x] 1.1 All obligation integration tests pass: `./mvnw test -Dtest=ObligationControllerIntegrationTest` — 09a08cd

### Phase 2: Frontend — Dialog fixes

#### Automated

- [x] 2.1 Angular build succeeds: `ng build` — 13ca3e4

#### Manual

- [ ] 2.2 Edit FIXED_TERM obligation → endDate and remainingPayments are greyed-out and non-interactive — styling fix in f1ef6b6, re-verify
- [x] 2.3 Edit RECURRING obligation → no FIXED_TERM fields shown; only amount and paymentDay editable
- [x] 2.4 Simulate delete failure → error message appears inside dialog; Cancel works
- [x] 2.5 Name field validation → error reads "Required (max 255 chars)"

### Phase 3: Toast notification system

#### Automated

- [x] 3.1 Angular build succeeds: `ng build` — 88c71b4

#### Manual

- [x] 3.2 Add obligation → "Obligation added." toast appears top-right, auto-dismisses ~3s
- [x] 3.3 Edit obligation → "Obligation updated." toast appears
- [x] 3.4 Delete obligation → "Obligation deleted." toast appears
- [ ] 3.5 Toast does not block "Add Obligation" button while visible — pointer-events-none fix in f1ef6b6, re-verify
- [x] 3.6 Dark mode: toast renders correctly
