# Expense and Income Tracking — Plan Brief

> Full plan: `context/changes/expense-income-tracking/plan.md`

## What & Why

Build v1.1 expense and income tracking on top of the shipped v0.1 obligation foundation. Users can log what they actually spent or earned, mark obligations as paid (auto-creating a linked transaction), and see a monthly dashboard with lazy-loaded trend charts — filling the gap between "what I owe" (obligations) and "what I actually paid."

## Starting Point

All S-01–S-04 slices (auth, obligations CRUD, edit/delete, email notifications) are done and archived. The obligation domain establishes the canonical pattern the transaction domain will mirror: user-scoped UUID entities, RFC 7807 error handling, `NextDueDateComputer` for recurrence, Angular signals + reactive forms. Flyway is at V6; next migrations are V7 and V8.

## Desired End State

The user can log one-off expenses and income entries (amount, date, category, optional description) or create recurring templates (paymentDay, period — mirrors obligations). On the obligations list, a "Mark Paid" button opens a mini-form that auto-creates a linked EXPENSE transaction and advances the obligation's `last_paid_date` so the notification scheduler skips a redundant alert. A `/dashboard` screen shows current-month totals (income / expenses / net) with category breakdown, and an expandable panel that lazily fetches and renders a 6-month bar chart via ng2-charts.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Data model | Unified `transactions` table (type = EXPENSE \| INCOME) | Half the code, single API endpoint, easier cross-type aggregation | Plan |
| Category taxonomy | Separate enum sets: ExpenseCategory (7 values) / IncomeCategory (5 values) | Purpose-based labels make dashboard breakdowns immediately meaningful | Plan |
| Recurrence model | Mirror obligation schema (RECURRING/FIXED_TERM + paymentDay) | Reuses `NextDueDateComputer` and all existing Angular recurrence UI with zero new logic | Plan |
| Obligation link | "Mark Paid" mini-form → auto-creates linked EXPENSE transaction | Closes the loop between obligation planning and actual payment without manual double-entry | Plan |
| Mark Paid semantics | Creates transaction + sets `obligation.last_paid_date`; scheduler skips if paid this cycle | Prevents a redundant notification after the user has already recorded payment | Plan |
| Dashboard default view | Monthly summary + category breakdown table (no chart) | Answers "where did my money go?" without requiring a charting library on first load | Plan |
| Dashboard extended view | 6-month trend bar chart via ng2-charts, lazy-loaded on first expand | Chart.js/ng2-charts is the standard Angular charting pair; lazy prevents the extra query on every dashboard open | Plan |
| Transaction editing | All fields editable (except `obligationId` FK) | Transactions are ledger entries — any field can be corrected; FK is immutable once the obligation link is set | Plan |
| Testing | Full Testcontainers + MockMvc integration tests for all three new controllers | Consistent quality bar with existing obligation tests; catches auth and DB constraint issues | Plan |
| ONE_OFF vs recurring in dashboard | Only ONE_OFF rows (`period IS NULL`) count toward monthly totals | Recurring entries are templates; actual payments are logged as ONE_OFF (via Mark Paid or manually) | Plan |

## Scope

**In scope:**
- ONE_OFF and RECURRING/FIXED_TERM transactions (unified table)
- EXPENSE categories: HOUSING, FOOD, TRANSPORT, UTILITIES, HEALTH, ENTERTAINMENT, OTHER
- INCOME categories: SALARY, FREELANCE, INVESTMENT, RENTAL, OTHER
- Optional description field (max 255 chars)
- "Mark Paid" button on obligations → mini-form → linked EXPENSE transaction
- Scheduler paid-cycle guard (skip notification if `last_paid_date` in current cycle)
- Dashboard: monthly summary + category breakdown (default) + 6-month trend chart (lazy)
- Full integration test coverage

**Out of scope:**
- Bank integrations or CSV import
- Multi-currency
- AI categorisation or budget recommendations
- Auto-generation of recurring transaction occurrences
- Obligation-to-transaction reconciliation report
- Re-linking a transaction to a different obligation after creation
- Rollback of `last_paid_date` when a linked transaction is deleted

## Architecture / Approach

New `transaction/` package mirrors the `obligation/` package structure. New `dashboard/` package with read-only service. `NextDueDateComputer` visibility widened to `public` so `TransactionService` can reuse it. The "Mark Paid" action is a method on `ObligationService` (cross-domain orchestration stays in the service layer, not the controller). Dashboard queries filter to `period IS NULL` rows only — the `idx_transactions_user_date` partial index covers this. Angular adds two feature modules (`transactions/`, `dashboard/`) and a `PaymentDialog` component inside the existing `obligations/` module.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. DB schema | V7 transactions table + V8 `last_paid_date` on obligations | Complex CHECK constraints on category/type — test against real Postgres, not H2 |
| 2. Transaction backend CRUD | Full CRUD endpoints + integration tests | Dual-enum category validation in service layer is the subtle spot |
| 3. Mark Paid integration | POST /api/obligations/{id}/pay + scheduler guard | Cross-domain orchestration (ObligationService → TransactionService) — check circular dependency risk |
| 4. Transaction frontend | Angular list, dialog, Mark Paid mini-form | TypeScript category union type must drive the picker correctly on type change |
| 5. Dashboard backend | /summary + /trends endpoints + integration tests | JPQL date filtering (YEAR/MONTH functions) must be verified against PostgreSQL |
| 6. Dashboard frontend | Angular KPI tiles + chart (ng2-charts) | ng2-charts + Angular 19 compatibility; chart renders correctly with empty/sparse data |

**Prerequisites:** All v0.1 slices done (confirmed). Local Postgres running. Railway env vars not needed for dev (fallback defaults in `application-local.yml`).

**Estimated effort:** ~4–6 sessions across 6 phases (after-hours pace).

## Open Risks & Assumptions

- `ObligationService` will inject `TransactionService` for the "Mark Paid" flow — check for circular Spring bean dependency; if it arises, extract the orchestration to a `PaymentFacade` service
- `NextDueDateComputer` is currently `final class` (package-private) — widening to `public` is a one-line change but must not break existing `ObligationService` tests
- ng2-charts v6 requires Angular 17+; project is on Angular 19 — should be compatible but verify on `npm install`
- JPQL `YEAR()` / `MONTH()` functions are JPQL-standard but behaviour should be confirmed against Postgres via the integration test before finalising the dashboard queries

## Success Criteria (Summary)

- User can add, edit, and delete expenses and income entries via `/transactions`
- "Mark Paid" on any obligation creates a linked transaction and suppresses the next redundant notification
- `/dashboard` shows correct monthly totals and category breakdowns, and the trend chart renders without errors after first expand
