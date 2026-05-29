# Add and List Obligations — Implementation Plan

## Overview

Implement full obligation CRUD (add, list, edit, delete) for FinanceHQ. The Angular UI is fully scaffolded and ready; this plan delivers the missing backend (entity, service, controller, migration) and then synchronises the frontend model to match the refined domain (ESSENTIAL/IMPORTANT/OPTIONAL category enum, FIXED_TERM fields, PLN amounts, computed nextDueDate). Once complete, the obligations dashboard will be functional end-to-end.

## Current State Analysis

- Auth layer is complete: User entity, JwtAuthenticationFilter, SecurityConfig, AuthController, integration tests with Testcontainers.
- Angular obligations UI is fully scaffolded: ObligationsComponent, ObligationDialogComponent, DeleteDialogComponent, ObligationsService, obligation.model.ts — all calling `/api/obligations` endpoints that do not yet exist.
- No Obligation entity, repository, service, controller, or DB migration exists on the backend.
- Frontend model uses the old category enum (TOP/HIGH/LOW), `id: string`, and is missing endDate/remainingPayments/nextDueDate fields.
- CORS allowed methods in SecurityConfig are missing PATCH — must be added before the edit endpoint can be called from the browser.

### Key Discoveries:

- `JwtAuthenticationFilter` sets `UserDetails` (which is the `User` entity) as the authentication principal — `@AuthenticationPrincipal User user` works in controllers.
- `GlobalExceptionHandler` returns `Map<String, String>` for all errors. New obligation-specific exceptions will return `ProblemDetail` (RFC 7807) via additional handler methods in the same class.
- Existing migrations are V1–V3; next is `V4__create_obligations_table.sql`.
- `SecurityConfig.corsConfigurationSource` allows `GET, POST, PUT, DELETE, OPTIONS` — `PATCH` is missing and must be added (`src/main/java/com/example/finance_hq/security/SecurityConfig.java:75`).
- `obligation-dialog.component.html:26` shows "Amount ($)" label — must change to PLN (zł).
- `obligations.component.html:53` displays `${{ o.amount.toLocaleString() }}` — must change to PLN.

## Desired End State

A logged-in user can add obligations (RECURRING or FIXED_TERM in PLN), see them listed with computed next due dates, edit amount and payment day, and delete with confirmation. All endpoints under `/api/obligations` are auth-guarded and user-scoped. The category enum is ESSENTIAL/IMPORTANT/OPTIONAL throughout frontend and backend.

### Key Discoveries:

- Entity package: `com.example.finance_hq.obligation`
- DTOs package: `com.example.finance_hq.obligation.dto`
- Test follows pattern at: `src/test/java/com/example/finance_hq/auth/AuthControllerIntegrationTest.java`
- Testcontainers config: `src/test/java/com/example/finance_hq/TestcontainersConfiguration.java`

## What We're NOT Doing

- No scheduler decrement of `remainingPayments` in this change — the field is stored and returned; the notification scheduler (FR-007) will own decrement logic.
- No automatic deactivation or hiding of obligations past their `endDate` — user manages deletion.
- No migration of auth error responses to RFC 7807 — only obligation-specific exceptions get ProblemDetail in this change.
- No notification logic (FR-007) — that is a separate change.
- No pagination — full list returned (single-user, small data).

## Implementation Approach

Five phases in dependency order: DB schema first, then service, then controller/DTOs, then frontend sync, then integration tests. Each phase is independently verifiable. The frontend changes (Phase 4) can be done in parallel with Phase 5 once Phase 3 is complete.

## Critical Implementation Details

**CORS PATCH fix** — `SecurityConfig.java:75` lists allowed methods as `GET, POST, PUT, DELETE, OPTIONS` but not `PATCH`. Add `"PATCH"` to the list or the edit dialog will be blocked by the browser's CORS preflight before it reaches Spring Security.

**nextDueDate computation** — For a given `paymentDay` (1–31) and `today`:
1. Clamp `paymentDay` to the last day of the current month; if result ≥ today, that is the candidate. Otherwise advance one month and clamp again.
2. For `FIXED_TERM`: if `endDate` is before `today`, return `null` (obligation complete). If the computed candidate is after `endDate`, return `endDate`.
This logic lives in a single utility (`NextDueDateComputer`) shared by the service and eventually the scheduler.

**FIXED_TERM service validation** — `CreateObligationRequest` carries `endDate` and `remainingPayments` as nullable fields (no annotation-level cross-field validator). The service checks: if `period == FIXED_TERM`, both must be non-null; throw a dedicated `InvalidObligationException extends RuntimeException` if violated. GlobalExceptionHandler maps `InvalidObligationException` → 400 ProblemDetail.

**Ownership pattern** — `ObligationRepository` exposes `findByIdAndUser(Long id, User user)` returning `Optional<Obligation>`. Service throws `ObligationNotFoundException` on empty result, regardless of whether the id exists for another user. GlobalExceptionHandler maps it to 404 ProblemDetail (no information leakage about other users' data).

---

## Phase 1: Database migration + JPA entity

### Overview

Create the obligations table and the JPA entity with enums and repository. No business logic yet — just persistence plumbing.

### Changes Required:

#### 1. Flyway migration

**File**: `src/main/resources/db/migration/V4__create_obligations_table.sql`

**Intent**: Create the obligations table with all domain fields, a foreign key to users, and an index on user_id for efficient per-user queries.

**Contract**: Table `obligations` with columns: `id BIGSERIAL PK`, `user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `name VARCHAR(255) NOT NULL`, `amount NUMERIC(15,2) NOT NULL CHECK (amount > 0)`, `category VARCHAR(20) NOT NULL`, `period VARCHAR(20) NOT NULL`, `payment_day SMALLINT NOT NULL CHECK (payment_day BETWEEN 1 AND 31)`, `end_date DATE` (nullable), `remaining_payments INT` (nullable), `created_at TIMESTAMP NOT NULL DEFAULT NOW()`. Index: `CREATE INDEX idx_obligations_user_id ON obligations(user_id)`.

#### 2. Category enum

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationCategory.java`

**Intent**: Enum representing obligation priority with three self-explanatory values.

**Contract**: `public enum ObligationCategory { ESSENTIAL, IMPORTANT, OPTIONAL }`

#### 3. Period enum

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationPeriod.java`

**Intent**: Enum distinguishing indefinitely recurring obligations from fixed-term ones.

**Contract**: `public enum ObligationPeriod { RECURRING, FIXED_TERM }`

#### 4. Obligation entity

**File**: `src/main/java/com/example/finance_hq/obligation/Obligation.java`

**Intent**: JPA entity mapping to the obligations table; does not implement any Spring Security interface (lessons.md: keep UserDetails adapters in auth layer).

**Contract**: `@Entity @Table(name = "obligations")`; fields: `Long id` (@GeneratedValue IDENTITY), `User user` (@ManyToOne @JoinColumn(name="user_id") not null), `String name`, `BigDecimal amount`, `ObligationCategory category` (@Enumerated(STRING)), `ObligationPeriod period` (@Enumerated(STRING)), `Integer paymentDay` (column `payment_day`), `LocalDate endDate` (column `end_date`, nullable), `Integer remainingPayments` (column `remaining_payments`, nullable), `LocalDateTime createdAt` (column `created_at`). No-arg constructor + full-field constructor; getters only (immutable after construction except for updates applied through setters on amount and paymentDay).

#### 5. Obligation repository

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationRepository.java`

**Intent**: JPA repository providing per-user queries and ownership-safe lookup.

**Contract**: `extends JpaRepository<Obligation, Long>`; two custom methods: `List<Obligation> findAllByUserOrderByCreatedAtDesc(User user)`; `Optional<Obligation> findByIdAndUser(Long id, User user)`.

### Success Criteria:

#### Automated Verification:

- Application context loads (Flyway applies V4): `./mvnw test -Dtest=FinanceHqApplicationTests`

#### Manual Verification:

- Connect to local DB and confirm `obligations` table exists with correct columns and index.
- Verify no existing data is affected.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Service layer

### Overview

Business logic for CRUD, ownership enforcement, and nextDueDate computation. No HTTP wiring yet.

### Changes Required:

#### 1. Custom exceptions

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationNotFoundException.java`

**Intent**: Thrown when an obligation is not found or belongs to a different user; maps to 404.

**Contract**: `extends RuntimeException`; constructor accepts a message string.

**File**: `src/main/java/com/example/finance_hq/obligation/InvalidObligationException.java`

**Intent**: Thrown when FIXED_TERM obligation is missing required endDate or remainingPayments; maps to 400.

**Contract**: `extends RuntimeException`; constructor accepts a message string.

#### 2. NextDueDateComputer

**File**: `src/main/java/com/example/finance_hq/obligation/NextDueDateComputer.java`

**Intent**: Pure utility class for computing the next due date from a paymentDay; isolated here so the scheduler (FR-007) can reuse the same logic without depending on the service.

**Contract**: `final class` with `static LocalDate compute(int paymentDay, LocalDate today, ObligationPeriod period, LocalDate endDate)`. Algorithm: clamp paymentDay to last day of current month; if result ≥ today use it, else advance to next month and clamp. For FIXED_TERM: if endDate is before today return null; if computed candidate is after endDate return endDate. Returns `LocalDate` or `null`.

#### 3. ObligationService

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationService.java`

**Intent**: Orchestrates obligation CRUD; enforces user scoping and FIXED_TERM validation; converts entities to response DTOs with computed nextDueDate.

**Contract**: `@Service`; constructor-injected `ObligationRepository`. Four methods:
- `List<ObligationResponse> findAll(User user)` — delegates to `findAllByUserOrderByCreatedAtDesc`, maps each to `ObligationResponse.from(o, NextDueDateComputer.compute(...))`.
- `ObligationResponse create(User user, CreateObligationRequest req)` — if period is FIXED_TERM and either endDate or remainingPayments is null, throw `InvalidObligationException("FIXED_TERM obligations require both endDate and remainingPayments")`; build and save entity; return response.
- `ObligationResponse update(User user, Long id, UpdateObligationRequest req)` — call `findByIdAndUser(id, user).orElseThrow(ObligationNotFoundException::new)`; apply non-null fields from req (amount and/or paymentDay); save; return updated response.
- `void delete(User user, Long id)` — same ownership lookup; delete.

### Success Criteria:

#### Automated Verification:

- Integration tests in Phase 5 cover all service paths through the HTTP layer.

#### Manual Verification:

- No manual step needed for this phase alone; proceed when Phase 3 compiles.

---

## Phase 3: REST controller + DTOs + security fix

### Overview

Expose four REST endpoints, add request/response DTOs with validation, wire RFC 7807 handlers for new exceptions, and fix the missing PATCH in CORS config.

### Changes Required:

#### 1. CreateObligationRequest DTO

**File**: `src/main/java/com/example/finance_hq/obligation/dto/CreateObligationRequest.java`

**Intent**: Validated request DTO for POST; annotation-level validation covers field constraints; cross-field FIXED_TERM check is in the service.

**Contract**: Java record with: `@NotBlank @Size(max=255) String name`; `@NotNull @DecimalMin("0.01") @Digits(integer=13, fraction=2) BigDecimal amount`; `@NotNull ObligationCategory category`; `@NotNull ObligationPeriod period`; `@NotNull @Min(1) @Max(31) Integer paymentDay`; `LocalDate endDate` (no annotation, nullable); `Integer remainingPayments` (no annotation, nullable).

#### 2. UpdateObligationRequest DTO

**File**: `src/main/java/com/example/finance_hq/obligation/dto/UpdateObligationRequest.java`

**Intent**: Partial update DTO for PATCH; either or both fields may be present; null means "do not update."

**Contract**: Java record with: `@DecimalMin("0.01") @Digits(integer=13, fraction=2) BigDecimal amount` (nullable); `@Min(1) @Max(31) Integer paymentDay` (nullable).

#### 3. ObligationResponse DTO

**File**: `src/main/java/com/example/finance_hq/obligation/dto/ObligationResponse.java`

**Intent**: Full API response shape including the computed nextDueDate.

**Contract**: Java record with: `Long id`, `String name`, `BigDecimal amount`, `ObligationCategory category`, `ObligationPeriod period`, `Integer paymentDay`, `LocalDate endDate`, `Integer remainingPayments`, `LocalDate nextDueDate`, `LocalDateTime createdAt`. Static factory: `ObligationResponse from(Obligation o, LocalDate nextDueDate)`.

#### 4. ObligationController

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationController.java`

**Intent**: REST controller for obligation CRUD; extracts authenticated user from Spring Security context using `@AuthenticationPrincipal`.

**Contract**: `@RestController @RequestMapping("/api/obligations")`; constructor-injected `ObligationService`:
- `GET /` → `ResponseEntity<List<ObligationResponse>>` 200
- `POST /` → `ResponseEntity<ObligationResponse>` 201 (Location header not required for MVP)
- `PATCH /{id}` → `ResponseEntity<ObligationResponse>` 200
- `DELETE /{id}` → `ResponseEntity<Void>` 204

Each method signature: `(@AuthenticationPrincipal User user, ...)`. The `User` principal is available because `JwtAuthenticationFilter` sets a `UsernamePasswordAuthenticationToken` with the `User` entity as principal.

#### 5. Update GlobalExceptionHandler

**File**: `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java`

**Intent**: Add RFC 7807 ProblemDetail handlers for the two new obligation exceptions, leaving existing auth handlers unchanged.

**Contract**: Add two `@ExceptionHandler` methods returning `ResponseEntity<ProblemDetail>`:
- `ObligationNotFoundException` → `ProblemDetail.forStatusAndDetail(404, ex.getMessage())` with title "Not Found"
- `InvalidObligationException` → `ProblemDetail.forStatusAndDetail(400, ex.getMessage())` with title "Validation Failed"

#### 6. Fix PATCH in CORS config

**File**: `src/main/java/com/example/finance_hq/security/SecurityConfig.java`

**Intent**: Add PATCH to the CORS allowed methods list so browser preflight requests for the edit endpoint succeed.

**Contract**: In `corsConfigurationSource()`, change `List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")` to `List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")`.

### Success Criteria:

#### Automated Verification:

- Backend compiles: `./mvnw clean package -DskipTests`
- Context loads: `./mvnw test -Dtest=FinanceHqApplicationTests`

#### Manual Verification:

- `GET /api/obligations` without token → 401
- `GET /api/obligations` with valid token → 200, empty array
- `POST /api/obligations` with valid RECURRING body and token → 201 with id and nextDueDate in response
- `POST /api/obligations` with `period: FIXED_TERM` and no endDate → 400 ProblemDetail
- `PATCH /api/obligations/{id}` with valid amount change → 200 with updated amount

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Frontend model + UI updates

### Overview

Synchronise the Angular frontend to the new backend contract: update the model types, rename the category enum, add FIXED_TERM conditional fields to the dialog, and display PLN amounts and nextDueDate in the list.

### Changes Required:

#### 1. obligation.model.ts

**File**: `src/main/frontend/src/app/features/obligations/obligation.model.ts`

**Intent**: Update all TypeScript types to match the new backend contract.

**Contract**:
```typescript
export interface Obligation {
  id: string;
  name: string;
  amount: number;
  category: 'ESSENTIAL' | 'IMPORTANT' | 'OPTIONAL';  // was TOP|HIGH|LOW
  period: 'RECURRING' | 'FIXED_TERM';
  paymentDay: number;
  endDate: string | null;                              // new
  remainingPayments: number | null;                   // new
  nextDueDate: string | null;                         // new, computed by backend
  createdAt: string;
}

export type CreateObligationDto = Omit<Obligation, 'id' | 'createdAt' | 'nextDueDate'>;
export type UpdateObligationDto = Partial<Pick<Obligation, 'amount' | 'paymentDay'>>;
```

#### 2. obligations.service.ts

**File**: `src/main/frontend/src/app/features/obligations/obligations.service.ts`

**Intent**: No changes to method signatures — `update(id: string, ...)` and `delete(id: string, ...)` already accept `id: string` which is UUID-compatible.

**Contract**: No changes needed. URL template literals work unchanged.

#### 3. obligation-dialog.component.ts

**File**: `src/main/frontend/src/app/features/obligations/obligation-dialog/obligation-dialog.component.ts`

**Intent**: Add endDate and remainingPayments controls that appear conditionally when period is FIXED_TERM; update category default and include new fields in the create payload.

**Contract**:
- Add `endDate: [null as string | null, []]` and `remainingPayments: [null as number | null, [Validators.min(1)]]` to the form group.
- Add a computed signal or getter `isFixedTerm` that returns `this.form.controls.period.value === 'FIXED_TERM'`.
- In `ngOnInit`, patch endDate and remainingPayments from the existing obligation if editing, and disable period/category (already done).
- Change default category value from `'TOP'` to `'ESSENTIAL'`.
- In `submit()`, include endDate and remainingPayments in the create payload when period is FIXED_TERM. For update, no change (PATCH only sends amount and paymentDay).

#### 4. obligation-dialog.component.html

**File**: `src/main/frontend/src/app/features/obligations/obligation-dialog/obligation-dialog.component.html`

**Intent**: Update category options to new enum values; add FIXED_TERM fields; change currency label.

**Contract**:
- Replace `<option value="TOP">TOP</option>` etc. with `ESSENTIAL`, `IMPORTANT`, `OPTIONAL` option values and matching readable labels.
- Change "Amount ($)" label to "Amount (PLN)".
- After the payment day row, add: `@if (isFixedTerm) { ... }` section with a date input for endDate and a number input for remainingPayments, each with required-when-visible validation message.

#### 5. category-badge.component.ts

**File**: `src/main/frontend/src/app/shared/ui/category-badge/category-badge.component.ts`

**Intent**: Update type and CSS class mapping for new category enum values.

**Contract**: Change `type Category = 'ESSENTIAL' | 'IMPORTANT' | 'OPTIONAL'`. Update the `classes` map: `ESSENTIAL` → red (same as TOP), `IMPORTANT` → amber (same as HIGH), `OPTIONAL` → green (same as LOW). Update `@Input` type.

#### 6. obligations.component.html

**File**: `src/main/frontend/src/app/features/obligations/obligations.component.html`

**Intent**: Add "NEXT DUE" column; change "$" to "zł" for PLN amounts.

**Contract**:
- Update grid template from `grid-cols-[2fr_1fr_1fr_1fr_64px]` to `grid-cols-[2fr_1fr_1fr_1fr_1fr_64px]` on both the header row and data rows.
- Add `'NEXT DUE'` to the header column array after `'DUE DAY'`.
- In data rows, add a `<p>` cell that shows `o.nextDueDate ?? '—'` (formatted or raw ISO string).
- Change `${{ o.amount.toLocaleString() }}` to `{{ o.amount.toLocaleString('pl-PL') }} zł`.

### Success Criteria:

#### Automated Verification:

- TypeScript compiles with no errors: `ng build` (from `src/main/frontend/`)

#### Manual Verification:

- Open dashboard in browser; verify obligations list loads (empty state shows).
- Add a RECURRING obligation — verify it appears in the table with name, amount in PLN, next due date column populated.
- Add a FIXED_TERM obligation — verify endDate and remainingPayments fields appear in the dialog, obligation saves and appears in the list.
- Edit an obligation's amount — verify updated value shown.
- Delete an obligation — verify it disappears after confirmation.
- Verify category badges show ESSENTIAL/IMPORTANT/OPTIONAL with correct colours.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 5: Integration tests

### Overview

Integration test suite for the obligations backend, following the Testcontainers + MockMvc pattern established in `AuthControllerIntegrationTest`.

### Changes Required:

#### 1. ObligationControllerIntegrationTest

**File**: `src/test/java/com/example/finance_hq/obligation/ObligationControllerIntegrationTest.java`

**Intent**: End-to-end integration tests for all obligation endpoints; uses a real PostgreSQL container; covers auth boundary, CRUD happy paths, validation errors, and ownership isolation.

**Contract**: `@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)`; helper methods to register and log in a test user and extract the access token; helper to build a valid RECURRING and FIXED_TERM create request body. Test cases:

Auth boundary:
- `GET /api/obligations` without token → 401
- `POST /api/obligations` without token → 401

CRUD happy paths:
- `GET /api/obligations` with token, no obligations → 200 with empty array
- `POST /api/obligations` valid RECURRING body → 201; response contains `id`, `nextDueDate` not null, `category: ESSENTIAL`
- `POST /api/obligations` valid FIXED_TERM body (with endDate + remainingPayments) → 201; response contains `endDate` and `remainingPayments`
- `GET /api/obligations` after creating one → 200 with array containing the obligation
- `PATCH /api/obligations/{id}` with `{ "amount": 999.99 }` → 200; response amount updated
- `DELETE /api/obligations/{id}` → 204; subsequent GET returns empty array

Validation:
- `POST /api/obligations` FIXED_TERM with missing endDate → 400
- `POST /api/obligations` FIXED_TERM with missing remainingPayments → 400
- `POST /api/obligations` with `amount: 0` → 400
- `POST /api/obligations` with `paymentDay: 32` → 400

Ownership:
- Create obligation as user A; attempt `PATCH` as user B → 404
- Create obligation as user A; attempt `DELETE` as user B → 404

### Success Criteria:

#### Automated Verification:

- All tests pass: `./mvnw test -Dtest=ObligationControllerIntegrationTest`
- Full test suite passes: `./mvnw test`

#### Manual Verification:

- Review test output; confirm each test scenario name is meaningful and covers the scenario described above.

---

## Testing Strategy

### Unit Tests:

- `NextDueDateComputer` is a pure function — add unit tests (`NextDueDateComputerTest`) covering: recurring on-day, recurring day past, month-end clamping (paymentDay 31 in February), FIXED_TERM with future endDate, FIXED_TERM with past endDate (returns null).

### Integration Tests:

- `ObligationControllerIntegrationTest` (Phase 5) — covers all HTTP layer scenarios against a real DB container.

### Manual Testing Steps:

1. Start backend locally (`./mvnw spring-boot:run --spring.profiles.active=local`) and Angular dev server (`ng serve`).
2. Register and log in via the UI.
3. Add a RECURRING obligation with paymentDay = today's day − 1 (should show next month as due date) and one with paymentDay = today's day + 2 (should show this month).
4. Add a FIXED_TERM obligation expiring next month; verify nextDueDate shows correctly.
5. Edit the amount; verify the updated value persists on page refresh.
6. Delete the obligation; confirm deletion prompt and verify it disappears.
7. Open browser DevTools Network tab — verify PATCH preflight returns 200 (not 403/blocked), confirming CORS fix.

## Migration Notes

No data migration needed — obligations table is new. Flyway V4 applies cleanly to both local and production databases.

## References

- PRD functional requirements: `context/foundation/prd.md` (FR-003 through FR-006)
- Lessons: `context/foundation/lessons.md` — Flyway auto-config module, RFC 7807, UserDetails separation, CORS static patterns
- Similar integration test: `src/test/java/com/example/finance_hq/auth/AuthControllerIntegrationTest.java`
- Existing entity pattern: `src/main/java/com/example/finance_hq/user/User.java`
- Existing controller pattern: `src/main/java/com/example/finance_hq/auth/AuthController.java`
- CORS bug location: `src/main/java/com/example/finance_hq/security/SecurityConfig.java:75`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Database migration + JPA entity

#### Automated

- [x] 1.1 Application context loads with V4 migration applied (`./mvnw test -Dtest=FinanceHqApplicationTests`) — 38e1cf9

#### Manual

- [x] 1.2 obligations table exists in local DB with correct columns and index

### Phase 2: Service layer

#### Automated

- [x] 2.1 Backend compiles after Phase 2 additions (`./mvnw clean package -DskipTests`) — 6dd0786

### Phase 3: REST controller + DTOs + security fix

#### Automated

- [x] 3.1 Backend compiles: `./mvnw clean package -DskipTests` — d90c5ee
- [x] 3.2 Context loads: `./mvnw test -Dtest=FinanceHqApplicationTests` — d90c5ee

#### Manual

- [x] 3.3 GET /api/obligations without token → 401 — d90c5ee
- [x] 3.4 GET /api/obligations with valid token → 200 empty array — d90c5ee
- [x] 3.5 POST valid RECURRING obligation → 201 with id and nextDueDate — d90c5ee
- [x] 3.6 POST FIXED_TERM missing endDate → 400 ProblemDetail — d90c5ee
- [x] 3.7 PATCH valid amount change → 200 updated response — d90c5ee

### Phase 4: Frontend model + UI updates

#### Automated

- [x] 4.1 TypeScript compiles with no errors: `ng build`

#### Manual

- [x] 4.2 Obligations list loads in browser (empty state shown)
- [x] 4.3 RECURRING obligation adds and appears with PLN amount and nextDueDate
- [x] 4.4 FIXED_TERM obligation shows conditional fields; saves correctly
- [x] 4.5 Edit updates amount; delete removes obligation
- [x] 4.6 Category badges show ESSENTIAL/IMPORTANT/OPTIONAL with correct colours

### Phase 5: Integration tests

#### Automated

- [ ] 5.1 ObligationControllerIntegrationTest all pass: `./mvnw test -Dtest=ObligationControllerIntegrationTest`
- [ ] 5.2 Full test suite passes: `./mvnw test`

#### Manual

- [ ] 5.3 Test output reviewed; scenario names meaningful and match plan
