# Add and List Obligations — Plan Brief

> Full plan: `context/changes/add-and-list-obligations/plan.md`

## What & Why

Implement the missing obligation CRUD backend so the Angular UI (already fully scaffolded) becomes functional end-to-end. This delivers FR-003 through FR-006: users can add, list, edit, and delete obligations in PLN with RECURRING or FIXED_TERM periods.

## Starting Point

Auth is complete (register, login, JWT, refresh tokens, Testcontainers integration tests). The Angular obligations UI — list component, dialogs, service, and model — is fully built and calls `/api/obligations` endpoints that do not yet exist. The frontend model uses the old `TOP/HIGH/LOW` category enum and is missing FIXED_TERM fields.

## Desired End State

A logged-in user can add RECURRING or FIXED_TERM obligations in PLN, see them listed with computed next due dates, edit amount and payment day, and delete with confirmation. Category is displayed as ESSENTIAL/IMPORTANT/OPTIONAL. All endpoints are auth-guarded and user-scoped.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Category enum rename | ESSENTIAL / IMPORTANT / OPTIONAL | TOP/HIGH/LOW is not self-explanatory | Plan |
| ID type | Long (BIGSERIAL) | Consistent with existing users/refresh_tokens; frontend updates id: string → number | Plan |
| FIXED_TERM end representation | endDate (LocalDate) + remainingPayments (Integer), both required | User needs both the calendar cutoff and payment count; no cross-check | Plan |
| Amount precision | BigDecimal / NUMERIC(15,2) | Correct for PLN financial data; grosz precision needed | Plan |
| paymentDay range | 1–31 with month-end clamping | Covers contracts specifying 30th/31st; clamping handles shorter months | Plan |
| Scope | Full CRUD (GET, POST, PATCH, DELETE) | Frontend has all four dialogs built; partial delivery would leave broken UI | Plan |
| nextDueDate | Computed in backend, returned in response | Single computation point reused by notification scheduler (FR-007) | Plan |
| Error format | RFC 7807 ProblemDetail for obligation exceptions | lessons.md tech-debt rule; auth errors stay as-is to avoid divergence in this change | Plan |
| remainingPayments lifecycle | Dynamic (scheduler decrements) — schema ready, scheduler deferred | User intent: tracks remaining installments; decrement logic belongs to FR-007 | Plan |
| PATCH semantics | Partial (either field independently) | REST PATCH semantics; user can fix just amount without resubmitting date | Plan |
| Testing | Integration tests, Testcontainers + MockMvc | Matches AuthControllerIntegrationTest pattern; catches real DB constraint and ownership bugs | Plan |

## Scope

**In scope:**
- V4 Flyway migration (`obligations` table)
- Obligation JPA entity + repository + enums
- ObligationService (CRUD, ownership, nextDueDate via NextDueDateComputer)
- ObligationController (4 endpoints) + DTOs + RFC 7807 exception handlers
- CORS PATCH fix (missing from SecurityConfig allowed methods)
- Frontend model sync (id type, category enum, new fields)
- obligation-dialog updates (FIXED_TERM conditional fields, PLN label)
- category-badge update (new enum values)
- obligations list template (NEXT DUE column, PLN display)
- Integration tests (ObligationControllerIntegrationTest)
- NextDueDateComputer unit tests

**Out of scope:**
- Notification scheduler / remainingPayments decrement (FR-007, separate change)
- Auto-deactivation of obligations past endDate
- Migration of auth error responses to RFC 7807
- Pagination

## Architecture / Approach

Backend follows the existing auth module layout: `com.example.finance_hq.obligation` package with entity, enums, repository, service, and controller; DTOs in a `dto/` sub-package. `NextDueDateComputer` is a standalone utility (no Spring context dependency) so the notification scheduler can import it without circular dependencies. Frontend model types are updated to match; the Angular service and dialog components get minimal targeted edits.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. DB migration + entity | obligations table, JPA entity, enums, repository | Flyway V4 must apply cleanly on local and prod |
| 2. Service layer | CRUD logic, ownership enforcement, nextDueDate computation | Month-end clamping edge cases in NextDueDateComputer |
| 3. Controller + DTOs + CORS fix | 4 REST endpoints, validation, RFC 7807, PATCH in CORS | CORS PATCH omission would silently block edit from browser |
| 4. Frontend sync | Model types, FIXED_TERM dialog fields, PLN display, category rename | TypeScript type errors if any field name mismatches |
| 5. Integration tests | 14 test cases covering happy paths, validation, ownership | Testcontainers startup time; test isolation between user A and user B |

**Prerequisites:** Local PostgreSQL running (for manual tests); backend builds with `./mvnw clean package`  
**Estimated effort:** ~2–3 sessions across 5 phases
