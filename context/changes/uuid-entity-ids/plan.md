# UUID Entity IDs — Implementation Plan

## Overview

Migrate all three entity primary keys (`User`, `RefreshToken`, `Obligation`) from `BIGSERIAL`/`Long` to PostgreSQL native `UUID`. Edit Flyway migrations V1, V2, V4 in-place (no production data exists). Update JPA entities and Spring Data repositories. The frontend already uses `string` for obligation IDs — no frontend changes needed.

## Current State Analysis

- Three entities use `@GeneratedValue(strategy = GenerationType.IDENTITY)` with `Long id`.
- Three Flyway migrations define `BIGSERIAL` PKs and `BIGINT` FK columns.
- Three repositories are typed `JpaRepository<T, Long>`.
- `ObligationRepository.findByIdAndUser(Long id, User user)` is the only custom method referencing `Long` directly for a lookup.
- No REST controllers yet expose obligation IDs in path variables (controller is Phase 3 of `add-and-list-obligations`).
- No DTOs expose entity `id` fields.
- Frontend `obligation.model.ts` already types `id` as `string` — UUID is wire-compatible.
- JWT subject is the user's email, not the user ID — no JWT changes needed.

### Key Discoveries:

- Spring Boot 4.x ships Hibernate 7.x, which fully implements JPA 3.2 including `GenerationType.UUID`. No `@GenericGenerator` legacy annotation needed.
- Hibernate 7.x auto-generates UUIDs **before** the INSERT (application-side, not DB-side). SQL columns need `UUID NOT NULL` with no `DEFAULT` clause.
- PostgreSQL JDBC driver maps `java.util.UUID` ↔ PostgreSQL `uuid` type natively. `@Column(columnDefinition = "uuid")` makes this explicit and avoids any VARCHAR fallback.
- FK columns referencing a UUID PK must also be `UUID`, not `BIGINT`.
- `GenerationType.IDENTITY` (relies on database sequence/serial) must be replaced by `GenerationType.UUID` on all three entities.
- Existing migrations have applied to local dev DB — a one-time local DB reset is required after in-place edits to clear the Flyway checksum validation.

## Desired End State

All primary key and foreign key columns in the database use the PostgreSQL `uuid` type. All JPA entities have `UUID id` with `GenerationType.UUID`. All repositories are typed `JpaRepository<T, UUID>`. The full test suite passes against a fresh Testcontainers DB. The `add-and-list-obligations` change can continue at Phase 2 with UUID-aware types from the start.

### Key Discoveries:

- Entity package: `com.example.finance_hq.user` (User, RefreshToken) and `com.example.finance_hq.obligation` (Obligation)
- Repository package: same as above
- Migration files: `src/main/resources/db/migration/V1__create_users_table.sql`, `V2__create_refresh_tokens_table.sql`, `V4__create_obligations_table.sql`
- `add-and-list-obligations` Phase 4 plan at `context/changes/add-and-list-obligations/plan.md` — line saying `id: number; // was string` must be removed from the obligation.model.ts contract (UUID renders as string)

## What We're NOT Doing

- No frontend changes — `id: string` is already UUID-compatible.
- No JWT changes — subject is email, not entity ID.
- No DTO changes — no DTOs expose entity IDs yet.
- No auth controller changes — no ID path variables exist there.
- No new Flyway ALTER migrations — in-place edits to V1/V2/V4 only.
- No change to `findByEmail`, `findByToken`, `deleteByToken`, `deleteByUser` — these don't reference Long IDs.

## Implementation Approach

Two phases in dependency order. Phase 1 delivers a bootable application (DB schema + JPA entity layer aligned on UUID). Phase 2 completes the repository type signatures and updates the coordination note in the sibling plan. The full test suite gates Phase 2.

## Critical Implementation Details

**No DB DEFAULT on UUID columns** — Hibernate assigns UUIDs before INSERT, so the SQL column is `UUID NOT NULL` with no `DEFAULT gen_random_uuid()`. Adding a DEFAULT is harmless but creates a misleading dual source of truth.

**Local DB reset required** — editing V1/V2/V4 after they have been applied changes their Flyway checksums. The app refuses to start until the local dev DB is dropped and recreated. Run before restarting the backend:
```sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
```
Or equivalently, drop and recreate the database. Testcontainers always starts fresh — no action needed for tests.

**add-and-list-obligations plan coordination** — Phase 4 of that plan specifies `id: number; // was string` in the `obligation.model.ts` contract. After this change lands, that line must be dropped: UUID is a string and the field stays `id: string` (already the frontend's current type). The coordinator must update the sibling plan's Phase 4 contract before implementing it.

---

## Phase 1: Migrations + JPA Entities

### Overview

Edit the three Flyway migration files to use `uuid` column types, then update all three JPA entities to use `UUID id` with `GenerationType.UUID`.

### Changes Required:

#### 1. V1 — users table primary key

**File**: `src/main/resources/db/migration/V1__create_users_table.sql`

**Intent**: Replace the `BIGSERIAL` primary key with a native PostgreSQL `UUID` column. Hibernate assigns the UUID value; no DB default is needed.

**Contract**: Change `id BIGSERIAL PRIMARY KEY` → `id UUID PRIMARY KEY`.

#### 2. V2 — refresh_tokens primary key and foreign key

**File**: `src/main/resources/db/migration/V2__create_refresh_tokens_table.sql`

**Intent**: Replace the `BIGSERIAL` PK with `UUID`, and update the `user_id` foreign key column type to match.

**Contract**: Change `id BIGSERIAL PRIMARY KEY` → `id UUID PRIMARY KEY`. Change `user_id BIGINT NOT NULL REFERENCES users(id)` → `user_id UUID NOT NULL REFERENCES users(id)`.

#### 3. V4 — obligations primary key and foreign key

**File**: `src/main/resources/db/migration/V4__create_obligations_table.sql`

**Intent**: Same pattern as V2 — replace PK and FK column types.

**Contract**: Change `id BIGSERIAL PRIMARY KEY` → `id UUID PRIMARY KEY`. Change `user_id BIGINT NOT NULL REFERENCES users(id)` → `user_id UUID NOT NULL REFERENCES users(id)`.

#### 4. User entity

**File**: `src/main/java/com/example/finance_hq/user/User.java`

**Intent**: Replace `Long id` with `UUID id` and switch the generation strategy so Hibernate auto-assigns a UUID before each INSERT.

**Contract**: Replace `@GeneratedValue(strategy = GenerationType.IDENTITY) private Long id` with `@GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "uuid") private UUID id`. Update `getId()` return type to `UUID`. Add `import java.util.UUID`.

#### 5. RefreshToken entity

**File**: `src/main/java/com/example/finance_hq/user/RefreshToken.java`

**Intent**: Same UUID migration pattern as User.

**Contract**: Replace `@GeneratedValue(strategy = GenerationType.IDENTITY) private Long id` with `@GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "uuid") private UUID id`. Update `getId()` return type to `UUID`. Add `import java.util.UUID`.

#### 6. Obligation entity

**File**: `src/main/java/com/example/finance_hq/obligation/Obligation.java`

**Intent**: Same UUID migration pattern as User and RefreshToken.

**Contract**: Replace `@GeneratedValue(strategy = GenerationType.IDENTITY) private Long id` with `@GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "uuid") private UUID id`. Update `getId()` return type to `UUID`. Add `import java.util.UUID`.

### Success Criteria:

#### Automated Verification:

- Application context loads with updated migrations: `./mvnw test -Dtest=FinanceHqApplicationTests`

#### Manual Verification:

- Reset local dev DB (see "Critical Implementation Details"), restart backend with `--spring.profiles.active=local`, confirm app starts cleanly.
- Connect to local DB; verify `users.id`, `refresh_tokens.id`, `obligations.id` are `uuid` type; verify `refresh_tokens.user_id` and `obligations.user_id` are `uuid` type.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human before proceeding.

---

## Phase 2: Repositories + Plan Coordination

### Overview

Update all three repository interfaces to use `UUID` as the ID type parameter. Update the single custom method that references `Long id` directly. Update the `add-and-list-obligations` sibling plan to remove the incorrect `id: number` frontend change.

### Changes Required:

#### 1. UserRepository

**File**: `src/main/java/com/example/finance_hq/user/UserRepository.java`

**Intent**: Align the JPA repository type parameter with the updated `User` entity.

**Contract**: Change `extends JpaRepository<User, Long>` → `extends JpaRepository<User, UUID>`. Add `import java.util.UUID`.

#### 2. RefreshTokenRepository

**File**: `src/main/java/com/example/finance_hq/user/RefreshTokenRepository.java`

**Intent**: Align with `RefreshToken` entity's new UUID id type. Custom query methods (`findByToken`, `deleteByToken`, `deleteByUser`, `findByTokenForUpdate`) do not reference `Long` — no changes needed there.

**Contract**: Change `extends JpaRepository<RefreshToken, Long>` → `extends JpaRepository<RefreshToken, UUID>`. Add `import java.util.UUID`.

#### 3. ObligationRepository

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationRepository.java`

**Intent**: Align with `Obligation` entity's new UUID id type and update the custom ownership-check method signature.

**Contract**: Change `extends JpaRepository<Obligation, Long>` → `extends JpaRepository<Obligation, UUID>`. Change `findByIdAndUser(Long id, User user)` → `findByIdAndUser(UUID id, User user)`. Add `import java.util.UUID`.

#### 4. Update add-and-list-obligations plan

**File**: `context/changes/add-and-list-obligations/plan.md`

**Intent**: Remove the `id: number` change from Phase 4's obligation.model.ts contract. UUID IDs are strings — the frontend field stays `id: string` (which is already the current type and needs no change at all in Phase 4).

**Contract**: In Phase 4's `obligation.model.ts` contract block, remove the line `id: number;  // was string` and the corresponding comment. The `id` field is unchanged in Phase 4 (it was `string`, stays `string`).

### Success Criteria:

#### Automated Verification:

- Full test suite passes: `./mvnw test`

#### Manual Verification:

- Log in via the UI; verify the auth flow still works end-to-end (register → login → refresh → logout).
- Confirm test output shows all existing tests passing with no UUID-related failures.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human before proceeding.

---

## Testing Strategy

### Unit Tests:

- No new unit tests needed — UUID generation is Hibernate-managed; the migration and entity layer is covered by the context-load test and integration tests.

### Integration Tests:

- Existing `AuthControllerIntegrationTest` covers register/login/refresh/logout against a real Testcontainers PostgreSQL DB — this is the primary integration gate.
- `FinanceHqApplicationTests` verifies context loads and Flyway migrations apply.

### Manual Testing Steps:

1. Reset local dev DB (drop and recreate schema).
2. Start backend with `--spring.profiles.active=local`.
3. Open `psql` and confirm UUID column types on all three tables.
4. Register a new user via the UI; observe a UUID (not integer) returned or stored.
5. Log in, refresh token, log out — confirm full auth cycle works.

## Migration Notes

**Local dev DB reset** — required once after editing V1/V2/V4 in-place:
```sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
```
Then restart the backend. Flyway applies all migrations from scratch.

**Testcontainers** — always creates a fresh DB per test run; no reset needed.

**add-and-list-obligations coordination** — this change must be fully merged and local DB reset before resuming `add-and-list-obligations` at Phase 2. Phase 4 of that plan no longer needs to change `id: string` → `id: number` (remove that line from the contract).

## References

- Existing entity pattern: `src/main/java/com/example/finance_hq/user/User.java`
- Sibling plan to coordinate: `context/changes/add-and-list-obligations/plan.md` (Phase 4)
- Hibernate 7.x UUID strategy: `GenerationType.UUID` (JPA 3.2, Jakarta EE 11)

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Migrations + JPA Entities

#### Automated

- [x] 1.1 Application context loads with updated migrations (`./mvnw test -Dtest=FinanceHqApplicationTests`) — 1a009a6

#### Manual

- [ ] 1.2 Local DB reset completed and backend starts cleanly
- [ ] 1.3 UUID column types confirmed in local DB for all three tables

### Phase 2: Repositories + Plan Coordination

#### Automated

- [x] 2.1 Full test suite passes (`./mvnw test`) — all 25 tests green — 81f423a

#### Manual

- [x] 2.2 Auth flow works end-to-end via UI after UUID migration — 81f423a
- [x] 2.3 add-and-list-obligations Phase 4 plan updated to remove `id: number` change — 81f423a
