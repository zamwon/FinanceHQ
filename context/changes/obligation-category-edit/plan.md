# Obligation Category Edit — Implementation Plan

## Overview

Allow users to change an obligation's category (ESSENTIAL / IMPORTANT / OPTIONAL) through the existing edit dialog. Currently, category is locked to read-only in edit mode. This unlocks it across the backend DTO, service validation, and the Angular dialog.

## Current State Analysis

Category is locked in three layers:

- **Backend DTO** — `UpdateObligationRequest` record contains only `amount` and `paymentDay`; category is not a field.
- **Backend service** — `ObligationService.update()` validates "at least one of amount or paymentDay", then applies only those two fields.
- **Frontend model** — `UpdateObligationDto` is `Partial<Pick<Obligation, 'amount' | 'paymentDay'>>`.
- **Frontend component** — `ngOnInit()` calls `this.form.controls.category.disable()` in edit mode; `submit()` sends only `{ amount, paymentDay }`.

The `category` column already exists in the database. No migration is needed.

## Desired End State

A user can open the edit dialog on any obligation, change its category, and save. The updated category is reflected immediately in the obligations list. Changing only the category (without modifying amount or paymentDay) is accepted by the backend.

### Key Discoveries

- `UpdateObligationRequest.java` — record at `src/main/java/com/example/finance_hq/obligation/dto/UpdateObligationRequest.java`
- `ObligationService.java:54-60` — validation and field-application logic to extend
- `obligation-dialog.component.ts:52,86` — the two lock points in the frontend
- `obligation.model.ts:15` — `UpdateObligationDto` type to extend
- Integration test pattern: `ObligationControllerIntegrationTest.java` — 5 existing PATCH tests follow a consistent register-create-patch-assert pattern

## What We're NOT Doing

- Period (`RECURRING` / `FIXED_TERM`) remains locked in edit mode — changing it has side-effects on endDate/remainingPayments that are out of scope here.
- No UI treatment distinguishing "editable in edit mode" from "locked in edit mode" beyond the existing disabled styling.

## Implementation Approach

Two sequential phases: fix the backend contract first, then wire up the frontend. Each phase is independently verifiable.

---

## Phase 1: Backend — add category to update contract

### Overview

Extend `UpdateObligationRequest`, update the "at least one field" validation to include category, apply the category change in the service, and add a focused integration test.

### Changes Required

#### 1. UpdateObligationRequest DTO

**File**: `src/main/java/com/example/finance_hq/obligation/dto/UpdateObligationRequest.java`

**Intent**: Add an optional `category` field so callers can supply a new category value in the PATCH request body.

**Contract**: Add `ObligationCategory category` as a third field in the record, no `@NotNull` annotation (it remains optional). Import `ObligationCategory`.

#### 2. ObligationService — validation + apply

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationService.java`

**Intent**: Extend the "at least one field" guard to include `category`, and apply the category value when it is provided.

**Contract**: Change the null-check on line 54 to `req.amount() == null && req.paymentDay() == null && req.category() == null`. After the existing `paymentDay` apply block, add `if (req.category() != null) obligation.setCategory(req.category());`. Confirm `Obligation` has a `setCategory` setter (it follows the same pattern as `setAmount` / `setPaymentDay`).

#### 3. Integration test — category update

**File**: `src/test/java/com/example/finance_hq/obligation/ObligationControllerIntegrationTest.java`

**Intent**: Add a test that PATCHes with only a category change and asserts the new category is returned.

**Contract**: Follow the existing `update_200_amountChanged()` pattern — register+login, create a recurring obligation (which defaults to ESSENTIAL), PATCH with `Map.of("category", "OPTIONAL")`, assert the response body's `"category"` field equals `"OPTIONAL"`. Name the test `update_200_categoryChanged`.

### Success Criteria

#### Automated Verification

- `./mvnw test` passes, including `update_200_categoryChanged`

#### Manual Verification

- `curl -X PATCH .../api/obligations/{id}` with `{"category":"OPTIONAL"}` returns 200 with updated category
- `curl -X PATCH` with `{}` (all nulls) still returns 400

**Implementation Note**: After automated verification passes, confirm manually before proceeding to Phase 2.

---

## Phase 2: Frontend — enable category in edit dialog

### Overview

Remove the `category.disable()` lock, include category in the update payload, and widen the `UpdateObligationDto` type.

### Changes Required

#### 1. UpdateObligationDto type

**File**: `src/main/frontend/src/app/features/obligations/obligation.model.ts`

**Intent**: Allow category to be included in update payloads sent to the service.

**Contract**: Change line 15 to `Partial<Pick<Obligation, 'amount' | 'paymentDay' | 'category'>>`.

#### 2. ObligationDialogComponent — remove category lock

**File**: `src/main/frontend/src/app/features/obligations/obligation-dialog/obligation-dialog.component.ts`

**Intent**: Remove the `category.disable()` call in edit mode so the dropdown is interactive, and include `category` in the update call.

**Contract**:
- Delete `this.form.controls.category.disable();` (line 52) from the `ngOnInit` edit block.
- In `submit()`, change the update branch (line 86) to: `this.svc.update(this.obligation!.id, { amount: v.amount!, paymentDay: v.paymentDay!, category: v.category! })`.
- The comment on line 54 currently lists `category` as a locked field — update it to remove category from the list.

### Success Criteria

#### Automated Verification

- `./mvnw test` still passes (no frontend compilation errors in the Maven build)

#### Manual Verification

- Open edit dialog on an obligation with category ESSENTIAL → category dropdown is enabled and shows ESSENTIAL
- Change to OPTIONAL, click Save → dialog closes, list shows OPTIONAL for that obligation
- Open edit dialog again → OPTIONAL is pre-selected
- Change only category (don't touch amount or paymentDay) → save succeeds (no 400 from backend)

**Implementation Note**: After manual UI verification passes, the change is complete.

---

## Testing Strategy

### Integration Tests

- `update_200_categoryChanged` — PATCH with `{"category":"OPTIONAL"}`, assert response category

### Manual Testing Steps

1. Start backend with local profile: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
2. Start Angular dev server: `cd src/main/frontend && ng serve`
3. Log in, find an obligation with category ESSENTIAL
4. Click Edit → verify category dropdown is enabled
5. Change to OPTIONAL → Save
6. Confirm list shows OPTIONAL
7. Re-open edit dialog → confirm OPTIONAL is pre-selected
8. Try editing only category (leave amount/paymentDay as-is) → confirm save succeeds

## References

- Original edit/delete obligations plan: `context/archive/2026-05-29-edit-and-delete-obligations/plan.md`
- PRD FR-005 (edit scope decision): `context/foundation/prd.md`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Backend — add category to update contract

#### Automated

- [x] 1.1 `./mvnw test` passes including `update_200_categoryChanged` — 43711ba

#### Manual

- [x] 1.2 PATCH with `{"category":"OPTIONAL"}` returns 200 with updated category — 43711ba
- [x] 1.3 PATCH with `{}` still returns 400 — 43711ba

### Phase 2: Frontend — enable category in edit dialog

#### Automated

- [x] 2.1 `./mvnw test` passes (no frontend compilation errors)

#### Manual

- [x] 2.2 Category dropdown is enabled in edit dialog
- [x] 2.3 Changing category persists and is reflected in the list
- [x] 2.4 Category-only save (amount/paymentDay unchanged) succeeds
