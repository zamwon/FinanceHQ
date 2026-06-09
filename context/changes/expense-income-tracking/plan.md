# Expense and Income Tracking Implementation Plan

## Overview

Build v1.1 expense and income tracking on top of the completed v0.1 obligation foundation. Users can log one-off and recurring transactions (expenses/income), mark obligations as paid (which auto-creates a linked transaction and updates the scheduler), and view a monthly dashboard with lazy-loaded trend charts.

## Current State Analysis

All v0.1 slices (F-01 through S-04) are done and archived. The codebase has:
- Obligations domain: entity, repo (user-scoped), service, controller, DTOs — canonical pattern to follow
- `ObligationPeriod` (RECURRING/FIXED_TERM) + `NextDueDateComputer` — reusable recurrence logic
- `notification_log` table + `NotificationScheduler` — existing scheduler to augment with paid-cycle guard
- Angular signals + reactive forms pattern (obligations feature module) — frontend template
- RFC 7807 `ProblemDetail` error handling in `GlobalExceptionHandler` — extend, not replace
- Flyway migrations V1–V6 applied; V7 is next

## Desired End State

After this plan completes:
- User can log one-off expenses/incomes (amount, date, category, optional description) and recurring templates (paymentDay, period, category)
- User can click "Mark Paid" on any obligation row — a mini-form opens pre-filled with the obligation's name and amount; user picks the expense category and confirms; a linked EXPENSE transaction is created and the obligation's `last_paid_date` is advanced so the scheduler won't send a redundant notification for that cycle
- `/transactions` list shows all entries (one-off sorted by date desc; recurring templates show `nextExpectedDate`)
- `/dashboard` shows current-month income, expenses, and net balance broken down by category; an expandable panel lazily fetches and renders a multi-month trend bar chart via ng2-charts
- Full integration test coverage at the same level as obligations

### Key Discoveries

- `obligation/NextDueDateComputer.java` is package-private; it needs to be made accessible to `TransactionService` (move to shared utility or open its visibility) — `src/main/java/com/example/finance_hq/obligation/NextDueDateComputer.java:13`
- `GlobalExceptionHandler` is in `auth/exception/` package — extend with two new exception handlers (`TransactionNotFoundException`, `InvalidTransactionException`) — `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java`
- `ObligationService.findAllSchedulerTargets` already fetches obligations with their user in one query; the "paid cycle" guard adds a `last_paid_date` check there — `src/main/java/com/example/finance_hq/obligation/ObligationService.java:75`
- Existing `DECIMAL(15,2)` / `NUMERIC(15,2)` pattern for amounts; `UUID DEFAULT gen_random_uuid()` for PKs — `src/main/resources/db/migration/V4__create_obligations_table.sql`
- Category validation cannot use a single Java enum (two disjoint sets per type); validate at service layer using two enums as allowed-values guards; the DB enforces the same with two `CHECK` constraints

## What We're NOT Doing

- Bank integrations / automated import — manual entry only
- Mobile push / SMS notifications for transactions
- Recurring transaction occurrence auto-generation — recurring entries are templates; actual payment occurrences are logged via "Mark Paid" or manually as one-off entries
- AI-powered categorisation or budget recommendations (v2)
- Bulk import (CSV/Excel)
- Multi-currency support
- Obligation-to-transaction reconciliation report (beyond dashboard "paid this cycle" indicator)
- Allowing the `obligation_id` FK to be changed after the transaction is created

## Implementation Approach

Build bottom-up: schema → backend CRUD → obligation integration → frontend → dashboard. Each phase is independently verifiable. The transaction domain mirrors the obligation domain structure to stay consistent; the "Mark Paid" integration is the only cross-domain concern and is isolated to Phase 3.

## Critical Implementation Details

**Category type-safety**: `ExpenseCategory` and `IncomeCategory` are separate Java enums but share one `category VARCHAR(50)` column in the DB. The entity stores category as `String`. Service-layer validation converts the raw string to the correct enum using `ExpenseCategory.valueOf()` / `IncomeCategory.valueOf()` wrapped in a try/catch, throwing `InvalidTransactionException` on mismatch. Two DB `CHECK` constraints independently enforce the same rule.

**ONE_OFF vs recurring date field**: ONE_OFF transactions populate `date` (nullable in schema, required by service when period is null). RECURRING/FIXED_TERM transactions have `payment_day` but `date` is null — `nextExpectedDate` is computed on every read using `NextDueDateComputer`. The dashboard summary aggregates only ONE_OFF rows (where `period IS NULL`) filtered by `date` — recurring templates are excluded from monthly totals.

**Paid-cycle guard in scheduler**: When `obligation.last_paid_date` is set, the scheduler skips the notification if `last_paid_date >= nextDueDate.minusMonths(1)` (i.e., the payment landed in the current monthly window). This check runs inside `NotificationService.runDailyNotifications` after `nextDueDate` is computed per obligation.

**`NextDueDateComputer` visibility**: The class is currently package-private (`final class`). Change to `public final class` so `transaction/TransactionService.java` can use it without duplicating the algorithm.

---

## Phase 1: Database Schema Migrations

### Overview

Create the `transactions` table and add `last_paid_date` to `obligations`. No Java code changes in this phase — just schema.

### Changes Required

#### 1. Transactions table

**File**: `src/main/resources/db/migration/V7__create_transactions_table.sql`

**Intent**: Create the unified transactions table with all fields for one-off and recurring entries, type discriminator, category validation, and optional FK to obligations.

**Contract**:
```sql
CREATE TABLE transactions (
    id                 UUID          NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    obligation_id      UUID          REFERENCES obligations(id) ON DELETE SET NULL,
    type               VARCHAR(10)   NOT NULL CHECK (type IN ('EXPENSE', 'INCOME')),
    category           VARCHAR(50)   NOT NULL,
    amount             NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    description        VARCHAR(255),
    period             VARCHAR(20)   CHECK (period IN ('RECURRING', 'FIXED_TERM')),
    date               DATE,
    payment_day        INT           CHECK (payment_day BETWEEN 1 AND 31),
    end_date           DATE,
    remaining_payments INT           CHECK (remaining_payments > 0),
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_one_off_has_date     CHECK (period IS NOT NULL OR date IS NOT NULL),
    CONSTRAINT chk_recurring_payment_day CHECK (period IS NULL OR payment_day IS NOT NULL),
    CONSTRAINT chk_fixed_term_complete  CHECK (period != 'FIXED_TERM' OR (end_date IS NOT NULL AND remaining_payments IS NOT NULL)),
    CONSTRAINT chk_category_expense     CHECK (type != 'EXPENSE' OR category IN ('HOUSING','FOOD','TRANSPORT','UTILITIES','HEALTH','ENTERTAINMENT','OTHER')),
    CONSTRAINT chk_category_income      CHECK (type != 'INCOME' OR category IN ('SALARY','FREELANCE','INVESTMENT','RENTAL','OTHER'))
);
CREATE INDEX idx_transactions_user_id   ON transactions(user_id);
CREATE INDEX idx_transactions_user_date ON transactions(user_id, date) WHERE date IS NOT NULL;
```

#### 2. Add last_paid_date to obligations

**File**: `src/main/resources/db/migration/V8__add_last_paid_date_to_obligations.sql`

**Intent**: Store the date of the most recent "Mark Paid" action per obligation so the notification scheduler can skip redundant notifications for already-paid cycles.

**Contract**: `ALTER TABLE obligations ADD COLUMN last_paid_date DATE;`

### Success Criteria

#### Automated Verification

- Migrations apply cleanly against local Postgres: `./mvnw flyway:migrate` (no errors)
- Both tables appear in schema history: `V7` and `V8` rows present in `flyway_schema_history`

#### Manual Verification

- `\d transactions` shows all columns, constraints, and indexes as specified
- `\d obligations` shows `last_paid_date DATE` column added

**Implementation Note**: After automated verification passes, pause for manual DB confirmation before proceeding to Phase 2.

---

## Phase 2: Transaction Domain — Backend CRUD

### Overview

Full CRUD for transactions mirroring the obligation domain: enums, entity, repository, service, controller, DTOs, exception classes, exception handler entries, and integration tests.

### Changes Required

#### 1. Make NextDueDateComputer public

**File**: `src/main/java/com/example/finance_hq/obligation/NextDueDateComputer.java`

**Intent**: Open visibility so `TransactionService` can reuse the recurrence algorithm without duplication.

**Contract**: Change `final class NextDueDateComputer` → `public final class NextDueDateComputer`. No other changes.

#### 2. TransactionType enum

**File**: `src/main/java/com/example/finance_hq/transaction/TransactionType.java`

**Intent**: Discriminate between expense and income rows in the unified table.

**Contract**: `public enum TransactionType { EXPENSE, INCOME }`

#### 3. ExpenseCategory enum

**File**: `src/main/java/com/example/finance_hq/transaction/ExpenseCategory.java`

**Intent**: Define the allowed category values for EXPENSE-type transactions.

**Contract**: `public enum ExpenseCategory { HOUSING, FOOD, TRANSPORT, UTILITIES, HEALTH, ENTERTAINMENT, OTHER }`

#### 4. IncomeCategory enum

**File**: `src/main/java/com/example/finance_hq/transaction/IncomeCategory.java`

**Intent**: Define the allowed category values for INCOME-type transactions.

**Contract**: `public enum IncomeCategory { SALARY, FREELANCE, INVESTMENT, RENTAL, OTHER }`

#### 5. Transaction entity

**File**: `src/main/java/com/example/finance_hq/transaction/Transaction.java`

**Intent**: JPA entity mapping to the `transactions` table. Stores category as `String` (not enum) because the field accepts values from two disjoint enums. All fields have setters (fully editable). The obligation FK is set at creation and has no setter (immutable link).

**Contract**:
- `@Entity @Table(name = "transactions")`
- UUID id with `@GeneratedValue(strategy = GenerationType.UUID)`
- `@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")` for user
- `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "obligation_id")` for obligation (nullable, no setter)
- `@Enumerated(EnumType.STRING) private TransactionType type`
- `private String category` (VARCHAR, validated by service)
- `private BigDecimal amount`, `private String description`
- `@Enumerated(EnumType.STRING) private ObligationPeriod period` (nullable — reuse existing enum)
- `private LocalDate date` (nullable — ONE_OFF only)
- `private Integer paymentDay`, `private LocalDate endDate`, `private Integer remainingPayments` (recurring fields)
- `@PrePersist` sets `createdAt` if null
- Setters on: type, category, amount, description, period, date, paymentDay, endDate, remainingPayments

#### 6. TransactionRepository

**File**: `src/main/java/com/example/finance_hq/transaction/TransactionRepository.java`

**Intent**: User-scoped data access following the same security pattern as `ObligationRepository`. Override `findById` to be unsupported; always use `findByIdAndUser`.

**Contract**:
- Extends `JpaRepository<Transaction, UUID>`
- `findAllByUser(User user, Pageable pageable): Page<Transaction>`
- `findByIdAndUser(UUID id, User user): Optional<Transaction>`
- Override `findById` to throw `UnsupportedOperationException` (same pattern as `ObligationRepository`)

#### 7. CreateTransactionRequest DTO

**File**: `src/main/java/com/example/finance_hq/transaction/dto/CreateTransactionRequest.java`

**Intent**: Validated record for transaction creation. Category is a raw string validated by service layer; period/date/paymentDay/endDate/remainingPayments follow the same conditional presence rules as obligations.

**Contract** (Java record):
- `@NotNull TransactionType type`
- `@NotBlank @Size(max=50) String category`
- `@NotNull @DecimalMin("0.01") @Digits(integer=13, fraction=2) BigDecimal amount`
- `@Size(max=255) String description` (nullable)
- `ObligationPeriod period` (nullable)
- `LocalDate date` (nullable — required when period is null, validated by service)
- `@Min(1) @Max(31) Integer paymentDay` (nullable)
- `LocalDate endDate` (nullable)
- `@Min(1) Integer remainingPayments` (nullable)
- `UUID obligationId` (nullable — set by "Mark Paid" flow only)

#### 8. UpdateTransactionRequest DTO

**File**: `src/main/java/com/example/finance_hq/transaction/dto/UpdateTransactionRequest.java`

**Intent**: All mutable transaction fields can be updated in a single PATCH. At least one must be non-null (service enforces). `obligationId` is NOT in this DTO — the FK is immutable.

**Contract** (Java record): same fields as `CreateTransactionRequest` minus `obligationId`, all nullable.

#### 9. TransactionResponse DTO

**File**: `src/main/java/com/example/finance_hq/transaction/dto/TransactionResponse.java`

**Intent**: Full transaction representation returned on all reads. Includes computed `nextExpectedDate` for recurring entries (null for ONE_OFF). Obligation is returned as a UUID reference only (no nested object).

**Contract** (Java record):
- All entity fields as-is
- `LocalDate nextExpectedDate` — computed from `NextDueDateComputer` when period is non-null, null when period is null
- `UUID obligationId` (nullable)
- Static factory: `TransactionResponse from(Transaction t, LocalDate nextExpectedDate)`

#### 10. TransactionNotFoundException

**File**: `src/main/java/com/example/finance_hq/transaction/TransactionNotFoundException.java`

**Intent**: Thrown when a transaction is not found or belongs to another user. No information leakage — always 404, never "belongs to another user".

**Contract**: `public class TransactionNotFoundException extends RuntimeException`

#### 11. InvalidTransactionException

**File**: `src/main/java/com/example/finance_hq/transaction/InvalidTransactionException.java`

**Intent**: Thrown for invalid business logic — unknown category for type, ONE_OFF missing date, FIXED_TERM missing fields, all-null update.

**Contract**: `public class InvalidTransactionException extends RuntimeException`

#### 12. TransactionService

**File**: `src/main/java/com/example/finance_hq/transaction/TransactionService.java`

**Intent**: Business logic for CRUD. Validates category against the correct enum for the given type. For ONE_OFF, requires `date`. For RECURRING/FIXED_TERM mirrors obligation validation. For updates, validates that at least one field is non-null and re-validates category/period consistency.

**Contract**:
- `findAll(User user): List<TransactionResponse>` — `@Transactional(readOnly=true)`, sorted by `createdAt DESC`, page 0 size 200
- `create(User user, CreateTransactionRequest req): TransactionResponse` — validates category vs type; validates ONE_OFF has date; validates FIXED_TERM has endDate + remainingPayments + endDate is in the future; resolves `obligation_id` FK by loading `Obligation` if `req.obligationId()` is non-null
- `update(User user, UUID id, UpdateTransactionRequest req): TransactionResponse` — re-validates category if type or category changes; at-least-one-field guard; uses `findByIdAndUser` for ownership
- `delete(User user, UUID id): void`
- Private `validateCategory(TransactionType type, String category)`: tries `ExpenseCategory.valueOf(category)` or `IncomeCategory.valueOf(category)` depending on type; throws `InvalidTransactionException` on `IllegalArgumentException`

#### 13. TransactionController

**File**: `src/main/java/com/example/finance_hq/transaction/TransactionController.java`

**Intent**: REST endpoints for transaction CRUD. Same pattern as `ObligationController`. No new security configuration needed — `/api/**` is already auth-required.

**Contract**:
- `@RestController @RequestMapping("/api/transactions")`
- `GET /` → 200 `List<TransactionResponse>`
- `POST /` → 201 `TransactionResponse`
- `PATCH /{id}` → 200 `TransactionResponse`
- `DELETE /{id}` → 204 no body
- All endpoints use `@AuthenticationPrincipal User user`

#### 14. GlobalExceptionHandler — add transaction handlers

**File**: `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java`

**Intent**: Register RFC 7807 handlers for the two new transaction exceptions, following the existing obligation handler pattern exactly.

**Contract**: Add two `@ExceptionHandler` methods:
- `TransactionNotFoundException` → 404 `ProblemDetail` titled "Not Found"
- `InvalidTransactionException` → 400 `ProblemDetail` titled "Validation Failed"

#### 15. TransactionControllerIntegrationTest

**File**: `src/test/java/com/example/finance_hq/transaction/TransactionControllerIntegrationTest.java`

**Intent**: Full integration coverage at the same level as the obligation tests. Must cover CRUD happy paths, ownership enforcement (cross-user 404), and business rule violations.

**Contract**: `@SpringBootTest @Transactional` class. Test cases:
- `GET /api/transactions` returns empty list for new user
- `POST /api/transactions` with ONE_OFF EXPENSE → 201 with correct fields
- `POST /api/transactions` with RECURRING INCOME → 201, nextExpectedDate computed
- `POST /api/transactions` with unknown category for type → 400
- `POST /api/transactions` with FIXED_TERM missing endDate → 400
- `PATCH /api/transactions/{id}` updates all provided fields → 200
- `PATCH /api/transactions/{id}` with all-null body → 400
- `DELETE /api/transactions/{id}` → 204; subsequent GET shows it gone
- Cross-user access: another user's token on `PATCH`/`DELETE` → 404

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=TransactionControllerIntegrationTest` passes (all cases green)
- `./mvnw clean package` produces a jar without compilation errors

#### Manual Verification

- `curl -H "Authorization: Bearer <token>" POST /api/transactions` with HOUSING expense → 201
- `curl GET /api/transactions` → list shows the created entry
- Verify `nextExpectedDate` is computed correctly for a RECURRING INCOME with paymentDay=25

**Implementation Note**: After all integration tests pass and manual curl verification succeeds, proceed to Phase 3.

---

## Phase 3: "Mark Paid" Obligation Integration

### Overview

Add a `POST /api/obligations/{id}/pay` endpoint that creates a linked EXPENSE transaction and advances `obligation.last_paid_date`. Update the notification scheduler to skip notifications for obligations already paid in the current cycle.

### Changes Required

#### 1. Obligation entity — add lastPaidDate

**File**: `src/main/java/com/example/finance_hq/obligation/Obligation.java`

**Intent**: Track the date of the most recent "Mark Paid" action so the scheduler can skip duplicate notifications.

**Contract**: Add `@Column(name = "last_paid_date") private LocalDate lastPaidDate;` plus getter and setter.

#### 2. MarkObligationPaidRequest DTO

**File**: `src/main/java/com/example/finance_hq/obligation/dto/MarkObligationPaidRequest.java`

**Intent**: The mini-form payload sent when the user confirms "Mark Paid". Amount is pre-filled client-side from the obligation but the server uses the submitted value (user may correct it).

**Contract** (Java record):
- `@NotNull @DecimalMin("0.01") @Digits(integer=13, fraction=2) BigDecimal amount`
- `@NotBlank @Size(max=50) String category` (must be a valid ExpenseCategory)
- `@Size(max=255) String description` (nullable)
- `@NotNull LocalDate date`

#### 3. ObligationService — add markPaid

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationService.java`

**Intent**: Atomic operation: verify obligation ownership, create an EXPENSE transaction linked to this obligation, set `obligation.lastPaidDate = req.date()`.

**Contract**: Add `@Transactional TransactionResponse markPaid(User user, UUID obligationId, MarkObligationPaidRequest req)`:
1. Load obligation via `findByIdAndUser` (throws `ObligationNotFoundException` if missing)
2. Build a `CreateTransactionRequest` with type=EXPENSE, obligationId=obligation.getId(), and all fields from `req`
3. Delegate to `transactionService.create(user, txnReq)` (inject `TransactionService` into `ObligationService`)
4. Set `obligation.lastPaidDate = req.date()` and `repository.save(obligation)`
5. Return the `TransactionResponse` from step 3

#### 4. ObligationController — add pay endpoint

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationController.java`

**Intent**: Expose the "Mark Paid" action as a dedicated POST endpoint. Returns the created transaction so the Angular client can update its transaction list without a separate fetch.

**Contract**: Add `@PostMapping("/{id}/pay") → 201 TransactionResponse`, delegating to `service.markPaid(user, id, req)`.

#### 5. NotificationService — add paid-cycle guard

**File**: `src/main/java/com/example/finance_hq/notification/NotificationService.java`

**Intent**: Skip sending a notification for obligations where `last_paid_date` falls in the current payment cycle (i.e., the user has already recorded a payment for this period).

**Contract**: In the loop inside `runDailyNotifications`, after computing `nextDueDate`, add:
```java
if (target.obligation().getLastPaidDate() != null &&
    !target.obligation().getLastPaidDate().isBefore(target.nextDueDate().minusMonths(1))) {
    continue; // already paid this cycle
}
```
Place this check before the `notification_log` query.

#### 6. ObligationPayIntegrationTest

**File**: `src/test/java/com/example/finance_hq/obligation/ObligationPayIntegrationTest.java`

**Intent**: Verify the "Mark Paid" flow end-to-end and the scheduler guard.

**Contract**: `@SpringBootTest @Transactional` class. Test cases:
- `POST /api/obligations/{id}/pay` with valid payload → 201, returned transaction has `obligationId` set
- Created transaction is retrievable via `GET /api/transactions`
- Obligation's `last_paid_date` is updated in DB (verify via repo query)
- Pay on another user's obligation → 404
- Scheduler guard: when `last_paid_date >= nextDueDate.minusMonths(1)`, notification is NOT sent (invoke `notificationService.runDailyNotifications(notificationDate)` directly and assert no email sent)

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=ObligationPayIntegrationTest` passes
- `./mvnw test` (full suite) still passes — no regressions in existing obligation or auth tests

#### Manual Verification

- Click "Mark Paid" flow end-to-end in the running app (Phase 4 will add the button — defer manual test to Phase 4)
- Verify obligation `last_paid_date` is set in DB after marking paid via curl

**Implementation Note**: Proceed to Phase 4 after full test suite passes.

---

## Phase 4: Transaction Frontend — CRUD

### Overview

Angular transaction list, dialog, and "Mark Paid" button on the obligations screen. Follows the obligations feature module pattern exactly.

### Changes Required

#### 1. ng2-charts dependency

**File**: `src/main/frontend/package.json`

**Intent**: Add the charting library now (used in Phase 6) so it's available when the dashboard is built. Installing early avoids a separate `npm install` in Phase 6.

**Contract**: Add `"ng2-charts": "^6.0.0"` and `"chart.js": "^4.0.0"` to `dependencies`. Run `npm install` in `src/main/frontend/`.

#### 2. Transaction model

**File**: `src/main/frontend/src/app/features/transactions/transaction.model.ts`

**Intent**: TypeScript interfaces matching the backend `TransactionResponse`. Two separate union types for categories to give compile-time safety in the dialog.

**Contract**:
```typescript
export type TransactionType = 'EXPENSE' | 'INCOME';
export type ExpenseCategory = 'HOUSING' | 'FOOD' | 'TRANSPORT' | 'UTILITIES' | 'HEALTH' | 'ENTERTAINMENT' | 'OTHER';
export type IncomeCategory = 'SALARY' | 'FREELANCE' | 'INVESTMENT' | 'RENTAL' | 'OTHER';
export type TransactionCategory = ExpenseCategory | IncomeCategory;

export interface Transaction {
  id: string;
  type: TransactionType;
  category: TransactionCategory;
  amount: number;
  description: string | null;
  period: 'RECURRING' | 'FIXED_TERM' | null;
  date: string | null;
  paymentDay: number | null;
  endDate: string | null;
  remainingPayments: number | null;
  nextExpectedDate: string | null;
  obligationId: string | null;
  createdAt: string;
}

export const EXPENSE_CATEGORIES: ExpenseCategory[] = ['HOUSING','FOOD','TRANSPORT','UTILITIES','HEALTH','ENTERTAINMENT','OTHER'];
export const INCOME_CATEGORIES: IncomeCategory[] = ['SALARY','FREELANCE','INVESTMENT','RENTAL','OTHER'];
```

#### 3. TransactionsService

**File**: `src/main/frontend/src/app/features/transactions/transactions.service.ts`

**Intent**: HTTP wrapper for all transaction endpoints. Same minimal pattern as `ObligationsService`.

**Contract**:
- `getAll(): Observable<Transaction[]>` → GET `/api/transactions`
- `create(dto): Observable<Transaction>` → POST `/api/transactions`
- `update(id, dto): Observable<Transaction>` → PATCH `/api/transactions/{id}`
- `delete(id): Observable<void>` → DELETE `/api/transactions/{id}`

#### 4. TransactionsComponent + template

**Files**: `src/main/frontend/src/app/features/transactions/transactions.component.ts` + `.html`

**Intent**: List view for all transactions. Signals for state (loading, error, list, editing). Same structural pattern as `ObligationsComponent`. ONE_OFF entries show their `date`; recurring entries show `nextExpectedDate`. Obligation-linked transactions show a paperclip indicator.

**Contract**: Signals: `transactions`, `loading`, `error`, `editingTransaction`, `showAddEdit`, `showDelete`. Methods: `load()`, `openAdd()`, `openEdit(t)`, `openDelete(t)`, `onSaved()`, `onDeleted()`. Template: table with columns — Type, Category, Amount, Date/Next Expected, Description, Obligation link, Actions (Edit, Delete).

#### 5. TransactionDialog + template

**Files**: `src/main/frontend/src/app/features/transactions/transaction-dialog/transaction-dialog.component.ts` + `.html`

**Intent**: Create and edit dialog. All fields are editable. Category picker dynamically shows only the correct set for the selected type (expense vs income categories). Period picker controls visibility of date vs paymentDay/endDate/remainingPayments fields, mirroring the obligation dialog. Obligation-linked transactions show the obligationId as read-only text (cannot re-link from UI).

**Contract**: `@Input() transaction: Transaction | null`. Form group: type, category, amount, description, period, date, paymentDay, endDate, remainingPayments. On `type` change: update category options signal and clear category control. On `period` change: toggle validators for date vs paymentDay/endDate/remainingPayments. `@Output() saved = new EventEmitter<void>()`.

#### 6. PaymentDialog + template (on ObligationsComponent)

**Files**: `src/main/frontend/src/app/features/obligations/payment-dialog/payment-dialog.component.ts` + `.html`

**Intent**: Mini-form opened when user clicks "Mark Paid" on an obligation row. Pre-fills amount and description from the obligation. Category picker shows ExpenseCategory list only. Date defaults to today. On submit: POST `/api/obligations/{id}/pay`.

**Contract**: `@Input() obligation: Obligation`. Form: amount (pre-filled), category (EXPENSE_CATEGORIES dropdown), description (pre-filled with `obligation.name`, optional), date (defaults to today). On success: `@Output() paid = new EventEmitter<void>()` — parent reloads obligations and emits to update transaction list if visible.

#### 7. ObligationsComponent — add Mark Paid button

**File**: `src/main/frontend/src/app/features/obligations/obligations.component.ts`

**Intent**: Add `showPayment` and `payingObligation` signals. Wire `openPayment(obligation)` and `onPaid()` methods. `onPaid()` reloads obligations and shows toast "Payment logged".

**File**: `src/main/frontend/src/app/features/obligations/obligations.component.html`

**Intent**: Add a "Mark Paid" button in each obligation row's action column. Conditionally render `<app-payment-dialog>` when `showPayment()` is true.

#### 8. App routes and navigation

**File**: `src/main/frontend/src/app/app.routes.ts`

**Intent**: Add `/transactions` route pointing to `TransactionsComponent` (lazy-loaded). Dashboard route deferred to Phase 6.

**File**: Navigation component (wherever the main nav lives — e.g., `core/layout/nav.component.html`)

**Intent**: Add "Transactions" nav link.

### Success Criteria

#### Automated Verification

- `ng build` (from `src/main/frontend/`) completes with no TypeScript errors
- `./mvnw test` (backend suite) still passes

#### Manual Verification

- Navigate to `/transactions` — shows empty state
- Add a FOOD expense with a date → appears in list with correct category label
- Add a SALARY income with RECURRING + paymentDay=25 → appears with computed nextExpectedDate
- Edit the expense → all fields editable; submit updates the row
- Delete an entry → confirmation dialog; entry removed
- On obligations page: click "Mark Paid" → mini-form opens with pre-filled amount and name; select category and confirm → obligation row remains; navigate to `/transactions` → linked transaction appears

**Implementation Note**: Complete all manual steps above before proceeding to Phase 5.

---

## Phase 5: Dashboard Backend

### Overview

Two read-only endpoints: a monthly summary (default view) and a multi-month trend series (lazy-loaded on user request). Only ONE_OFF transactions contribute to monthly totals.

### Changes Required

#### 1. MonthlySummaryResponse DTO

**File**: `src/main/java/com/example/finance_hq/dashboard/dto/MonthlySummaryResponse.java`

**Intent**: Encapsulate the monthly totals and category breakdown for a single month.

**Contract** (Java record):
- `String month` (format: "YYYY-MM")
- `BigDecimal totalIncome`
- `BigDecimal totalExpenses`
- `BigDecimal netBalance` (totalIncome - totalExpenses)
- `List<CategoryBreakdownItem> expensesByCategory`
- `List<CategoryBreakdownItem> incomeByCategory`

#### 2. CategoryBreakdownItem DTO

**File**: `src/main/java/com/example/finance_hq/dashboard/dto/CategoryBreakdownItem.java`

**Intent**: Single row in the category breakdown table.

**Contract** (Java record): `String category`, `BigDecimal total`, `long count`

#### 3. MonthlyTrendItem DTO

**File**: `src/main/java/com/example/finance_hq/dashboard/dto/MonthlyTrendItem.java`

**Intent**: Single data point in the trend series (one month).

**Contract** (Java record): `String month`, `BigDecimal totalIncome`, `BigDecimal totalExpenses`, `BigDecimal netBalance`

#### 4. DashboardRepository queries

**File**: `src/main/java/com/example/finance_hq/dashboard/DashboardRepository.java`

**Intent**: JPQL queries scoped to a user's ONE_OFF transactions for a given month. A separate query aggregates by category. Trend query fetches N months in one call.

**Contract**: Interface extending `Repository<Transaction, UUID>`:
- `@Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.user = :user AND t.period IS NULL AND t.type = :type AND YEAR(t.date) = :year AND MONTH(t.date) = :month")` — `sumByTypeAndMonth`
- `@Query("SELECT t.category, SUM(t.amount), COUNT(t) FROM Transaction t WHERE t.user = :user AND t.period IS NULL AND t.type = :type AND YEAR(t.date) = :year AND MONTH(t.date) = :month GROUP BY t.category")` — `categoryBreakdown`
- Trend query: same but iterating over N months (or a date range query with GROUP BY year/month)

#### 5. DashboardService

**File**: `src/main/java/com/example/finance_hq/dashboard/DashboardService.java`

**Intent**: Compute monthly summary and trend series from the repository queries. All `@Transactional(readOnly = true)`.

**Contract**:
- `getMonthlySummary(User user, YearMonth month): MonthlySummaryResponse`
- `getMonthlyTrend(User user, int months): List<MonthlyTrendItem>` — returns the last `months` months in ascending order

#### 6. DashboardController

**File**: `src/main/java/com/example/finance_hq/dashboard/DashboardController.java`

**Intent**: Two GET endpoints. `month` defaults to current month if omitted. `months` defaults to 6.

**Contract**:
- `@RestController @RequestMapping("/api/dashboard")`
- `GET /summary?month=YYYY-MM` → 200 `MonthlySummaryResponse`
- `GET /trends?months=N` → 200 `List<MonthlyTrendItem>`

#### 7. DashboardControllerIntegrationTest

**File**: `src/test/java/com/example/finance_hq/dashboard/DashboardControllerIntegrationTest.java`

**Intent**: Verify aggregation logic with seeded transaction data.

**Contract**: `@SpringBootTest @Transactional`. Test cases:
- Empty month → summary shows zeros
- Two EXPENSE + one INCOME in same month → correct totals and net balance
- RECURRING transaction in same month does NOT appear in totals
- Category breakdown groups correctly (two FOOD entries → one FOOD row)
- Trend endpoint returns N months in ascending order; current month matches summary

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=DashboardControllerIntegrationTest` passes
- `./mvnw test` (full suite) passes

#### Manual Verification

- `curl GET /api/dashboard/summary` → JSON with totals matching manually seeded test data
- `curl GET /api/dashboard/trends?months=3` → 3-item array in ascending month order

**Implementation Note**: Proceed to Phase 6 only after manual verification of both endpoints.

---

## Phase 6: Dashboard Frontend

### Overview

Angular dashboard component with the two-tier layout: default monthly summary + category breakdown table; expandable panel that lazily calls the trends endpoint and renders a bar chart via ng2-charts.

### Changes Required

#### 1. Dashboard model

**File**: `src/main/frontend/src/app/features/dashboard/dashboard.model.ts`

**Intent**: TypeScript interfaces matching the backend DTOs.

**Contract**:
```typescript
export interface CategoryBreakdownItem { category: string; total: number; count: number; }
export interface MonthlySummaryResponse {
  month: string; totalIncome: number; totalExpenses: number; netBalance: number;
  expensesByCategory: CategoryBreakdownItem[]; incomeByCategory: CategoryBreakdownItem[];
}
export interface MonthlyTrendItem { month: string; totalIncome: number; totalExpenses: number; netBalance: number; }
```

#### 2. DashboardService (Angular)

**File**: `src/main/frontend/src/app/features/dashboard/dashboard.service.ts`

**Intent**: Two HTTP calls: summary (always on load) and trends (on demand).

**Contract**:
- `getSummary(month?: string): Observable<MonthlySummaryResponse>` → GET `/api/dashboard/summary?month=...`
- `getTrends(months: number): Observable<MonthlyTrendItem[]>` → GET `/api/dashboard/trends?months=...`

#### 3. DashboardComponent + template

**Files**: `src/main/frontend/src/app/features/dashboard/dashboard.component.ts` + `.html`

**Intent**: Two-section layout. Top section (always visible): month picker → on change re-fetch summary; three KPI tiles (income / expenses / net); category breakdown tables for expenses and income. Bottom section (collapsed by default): "Show Trends" button; on click, calls `getTrends(6)` once and renders a bar chart; toggles between Show/Hide.

**Contract** (TypeScript):
- Signals: `summary`, `trends`, `loadingSummary`, `loadingTrends`, `error`, `trendsVisible`
- `selectedMonth` signal (YearMonth string, defaults to current month)
- `loadSummary()` called on init and on month change
- `toggleTrends()` fetches trends on first expand only (lazy — use a `trendsLoaded` flag signal)
- ng2-charts: `ChartData<'bar'>` built from `MonthlyTrendItem[]` array; labels = months, datasets = income and expenses series

**Template layout**: Month picker input at top. Three KPI number tiles. Two side-by-side tables (expenses by category, income by category). Separator. "Show Trends ▼" / "Hide Trends ▲" toggle button. Collapsible bar chart panel.

#### 4. App routes + navigation

**File**: `src/main/frontend/src/app/app.routes.ts`

**Intent**: Add `/dashboard` route (lazy-loaded).

**File**: Navigation component

**Intent**: Add "Dashboard" nav link.

### Success Criteria

#### Automated Verification

- `ng build` passes with no TypeScript errors (including ng2-charts types)
- `./mvnw test` (backend) still passes

#### Manual Verification

- Navigate to `/dashboard` — KPI tiles show zeros for a fresh user
- After seeding 3 transactions via `/transactions` form, return to `/dashboard` → totals update
- Change month picker → summary re-fetches
- Click "Show Trends" → chart renders with 6 months of bars; click "Hide Trends" → chart collapses
- Obligation-linked transactions appear correctly in category breakdown
- No console errors; no layout regressions on `/transactions` or `/obligations`

**Implementation Note**: This is the final phase. After all manual steps pass, mark the change as complete.

---

## Testing Strategy

### Unit Tests

- `TransactionService` validation logic (category enum mismatch, ONE_OFF missing date, all-null update)
- `DashboardService` trend boundary logic (first and last month, N=1 edge case)
- `NextDueDateComputer` — already tested; verify no regressions after visibility change

### Integration Tests

- `TransactionControllerIntegrationTest` — full CRUD + ownership + validation (Phase 2)
- `ObligationPayIntegrationTest` — Mark Paid flow + scheduler guard (Phase 3)
- `DashboardControllerIntegrationTest` — aggregation, category grouping, trend order (Phase 5)

### Manual Testing Steps

1. Add a HOUSING expense ($500, today) and a FOOD expense ($100, today) — dashboard shows $600 total expenses
2. Add a SALARY income ($3000, today) — net balance shows $2400
3. Add a RECURRING SALARY income (paymentDay=25) — does NOT appear in monthly total; appears in transaction list with nextExpectedDate
4. Mark an obligation as "paid" — verify: transaction linked to obligation appears in list; obligation `last_paid_date` updated; scheduler skips notification for that cycle
5. Edit the linked transaction's category (OTHER → HOUSING) — verify dashboard category breakdown updates
6. Delete the linked transaction — verify it's gone from list; obligation `last_paid_date` is NOT rolled back (acceptable — it was paid, just the log was removed)
7. Expand "Show Trends" — verify chart bars for current month match the dashboard KPIs

## Performance Considerations

- Dashboard queries filter to ONE_OFF rows (`period IS NULL`) — the `idx_transactions_user_date` partial index covers this efficiently
- Trend endpoint queries up to 12 months of data for a single user; no pagination needed at personal scale
- Lazy loading the trends endpoint avoids a second DB query on every dashboard open

## Migration Notes

No existing data to migrate. V7 and V8 apply to a live schema that already has obligations and users data — both migrations are purely additive (new table + new nullable column), safe to apply with zero downtime.

## References

- Obligation domain pattern: `src/main/java/com/example/finance_hq/obligation/`
- NextDueDateComputer: `src/main/java/com/example/finance_hq/obligation/NextDueDateComputer.java:13`
- GlobalExceptionHandler: `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java`
- NotificationScheduler: `src/main/java/com/example/finance_hq/notification/NotificationScheduler.java`
- Obligations Angular pattern: `src/main/frontend/src/app/features/obligations/`
- ng2-charts docs: https://valor-software.com/ng2-charts/

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Database Schema Migrations

#### Automated

- [x] 1.1 Migrations apply cleanly — no errors from `./mvnw flyway:migrate`
- [x] 1.2 V7 and V8 rows present in `flyway_schema_history`

#### Manual

- [x] 1.3 `\d transactions` confirms all columns, constraints, and indexes
- [x] 1.4 `\d obligations` shows `last_paid_date DATE` column added

### Phase 2: Transaction Domain — Backend CRUD

#### Automated

- [ ] 2.1 `TransactionControllerIntegrationTest` — all cases pass
- [ ] 2.2 `./mvnw clean package` succeeds with no compilation errors

#### Manual

- [ ] 2.3 `curl POST /api/transactions` creates a HOUSING expense → 201
- [ ] 2.4 `curl GET /api/transactions` lists the created entry
- [ ] 2.5 `nextExpectedDate` is computed correctly for a RECURRING INCOME

### Phase 3: "Mark Paid" Obligation Integration

#### Automated

- [ ] 3.1 `ObligationPayIntegrationTest` — all cases pass
- [ ] 3.2 Full `./mvnw test` suite passes (no obligation or auth regressions)

#### Manual

- [ ] 3.3 `curl POST /api/obligations/{id}/pay` creates a linked transaction and updates `last_paid_date`

### Phase 4: Transaction Frontend — CRUD

#### Automated

- [ ] 4.1 `ng build` completes with no TypeScript errors
- [ ] 4.2 `./mvnw test` (backend suite) still passes

#### Manual

- [ ] 4.3 `/transactions` shows empty state for new user
- [ ] 4.4 Add FOOD expense → appears in list with correct category label
- [ ] 4.5 Add RECURRING SALARY → shows computed nextExpectedDate
- [ ] 4.6 Edit expense → all fields editable; update succeeds
- [ ] 4.7 Delete entry → confirmation dialog; entry removed
- [ ] 4.8 "Mark Paid" on obligation → mini-form; confirm → linked transaction in `/transactions`

### Phase 5: Dashboard Backend

#### Automated

- [ ] 5.1 `DashboardControllerIntegrationTest` — all cases pass
- [ ] 5.2 Full `./mvnw test` suite passes

#### Manual

- [ ] 5.3 `curl GET /api/dashboard/summary` returns correct totals for seeded data
- [ ] 5.4 `curl GET /api/dashboard/trends?months=3` returns 3-item array in ascending order

### Phase 6: Dashboard Frontend

#### Automated

- [ ] 6.1 `ng build` passes with no TypeScript errors (including ng2-charts types)
- [ ] 6.2 `./mvnw test` (backend suite) still passes

#### Manual

- [ ] 6.3 Dashboard KPI tiles reflect seeded transaction data
- [ ] 6.4 Month picker change re-fetches summary
- [ ] 6.5 "Show Trends" renders 6-month bar chart; "Hide Trends" collapses it
- [ ] 6.6 No console errors; no regressions on `/transactions` or `/obligations`
