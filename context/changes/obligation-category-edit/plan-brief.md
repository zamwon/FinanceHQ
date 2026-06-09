# Obligation Category Edit — Plan Brief

> Full plan: `context/changes/obligation-category-edit/plan.md`

## What & Why

Allow users to change an obligation's category (ESSENTIAL / IMPORTANT / OPTIONAL) via the existing edit dialog. The field is currently locked in edit mode — a deliberate PRD decision (FR-005) that the user is now overriding. The motivation is simple usability: users should be able to reclassify an obligation without deleting and re-creating it.

## Starting Point

Category is locked in three layers today: the `UpdateObligationRequest` DTO has no category field, `ObligationService.update()` only reads amount/paymentDay, and the Angular form explicitly disables the category control in edit mode. The `category` column already exists in the DB, so no migration is needed.

## Desired End State

A user opens the edit dialog, the category dropdown is interactive (not greyed out), they pick a new value, hit Save, and the list immediately reflects the change. Changing only the category — without touching amount or paymentDay — is a valid update.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Scope | Category only (period stays locked) | Changing period has endDate/remainingPayments side-effects that add real complexity | Plan |
| Validation | Include category in the "at least one field" check | Allows category-only updates while still rejecting empty PATCH requests | Plan |
| Test coverage | Add `update_200_categoryChanged` integration test | Prevents regression if category gets re-locked in a future refactor | Plan |

## Scope

**In scope:** Unlock category in the edit dialog; update backend DTO, service, and integration tests; update frontend model and component.

**Out of scope:** Period field remains locked. No UI styling changes. No new validation rules on category values (enum is already validated by Jackson).

## Architecture / Approach

Purely additive unlock across two layers. Backend first (DTO + service + test), then frontend (model type + component). No schema change, no new files — only modifications to 4 existing files.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Backend unlock | `UpdateObligationRequest` carries category; service applies it; integration test covers it | Forgetting to update the "at least one field" null-check → 400 on category-only saves |
| 2. Frontend unlock | Category dropdown enabled in edit mode; payload includes category | `form.getRawValue()` already captures disabled controls via `getRawValue()` — but once enabled, the value is live and must be sent explicitly |

**Prerequisites:** None — this is a standalone change with no external dependencies.  
**Estimated effort:** ~1 session across 2 phases.

## Open Risks & Assumptions

- `Obligation` entity has a `setCategory()` setter — confirmed by the existing `setAmount` / `setPaymentDay` pattern; verify before Phase 1.
- The Angular form uses `getRawValue()` which captures disabled controls anyway — unlocking category won't break the existing form value reading.

## Success Criteria (Summary)

- Category dropdown is interactive in the edit dialog (not disabled)
- Changing only category and saving returns 200 and persists the new value
- `./mvnw test` passes including the new `update_200_categoryChanged` test
