---
date: 2026-06-02T14:00:00+02:00
researcher: Claude Sonnet 4.6
git_commit: 6639e611e9e754397f0c066493307b4b3b550917
branch: master
repository: FinanceHQ
topic: "Phase 2 — Data integrity and access control (Risks #3 and #4)"
tags: [research, codebase, obligation, flyway, migration, idor, data-integrity, access-control]
status: complete
last_updated: 2026-06-02
last_updated_by: Claude Sonnet 4.6
---

# Research: Phase 2 — Data integrity and access control (Risks #3 and #4)

**Date**: 2026-06-02  
**Researcher**: Claude Sonnet 4.6  
**Git Commit**: 6639e611e9e754397f0c066493307b4b3b550917  
**Branch**: master  
**Repository**: FinanceHQ

---

## Research Question

What is the exact state of obligation data integrity (Flyway migration chain, entity/column type alignment, field round-trip correctness) and access control (IDOR surface across all endpoints that accept an obligation ID), and what tests are missing to prove protection against Risks #3 and #4?

---

## Summary

**Risk #3 (Data integrity):** The migration chain is sound — all 7 migrations are additive (no DROP COLUMN, no data-modifying DDL) and `ddl-auto=none` prevents Hibernate from touching the schema. Entity-to-column type alignment is correct. The critical gap is **no existing test asserts full field-level round-trip correctness** for a FIXED_TERM obligation — existing tests check status codes and a handful of fields, not that `amount` preserves 2 decimal places, `endDate` and `remainingPayments` round-trip intact, and `createdAt` is not reset after a PATCH.

**Risk #4 (IDOR):** The ownership enforcement pattern is solid and **already tested** for the current API surface (PATCH, DELETE, GET list all have ownership boundary tests in `ObligationControllerIntegrationTest`). No GET `/api/obligations/{id}` endpoint exists, so there is no untested individual-read surface. The one documented gap is the inherited `findById(UUID)` from JpaRepository — it is available but deliberately unused in all production paths; this was consciously accepted as MVP risk in impl-review-phase-1 (F2).

**Phase 2 test writing effort is therefore asymmetric:** Risk #4 needs only one supplemental test (update with nonexistent ID → 404, proving the same error response as wrong-user access). Risk #3 needs a new integration test class with field-level assertions.

---

## Detailed Findings

### Risk #3: Flyway migration chain

Migration files: `src/main/resources/db/migration/`

| Version | File | What it does | Data integrity impact |
|---------|------|-------------|----------------------|
| V1 | `V1__create_users_table.sql` | Creates `users` (UUID PK, email UNIQUE NOT NULL, password_hash NOT NULL) | Foundation — FK target for obligations |
| V2 | `V2__create_refresh_tokens_table.sql` | Creates `refresh_tokens` with FK → users ON DELETE CASCADE | Auth layer — no impact on obligations |
| V3 | `V3__add_refresh_tokens_indexes.sql` | Indexes on refresh_tokens | No impact |
| V4 | `V4__create_obligations_table.sql` | Creates `obligations` — all columns, CHECK constraints, FK → users ON DELETE CASCADE | Core schema |
| V5 | `V5__add_remaining_payments_check.sql` | `ALTER TABLE obligations ADD CONSTRAINT ... CHECK (remaining_payments IS NULL OR remaining_payments > 0)` | **Additive only** — NULL rows unaffected; existing rows with valid values unaffected |
| V6 | `V6__create_notification_log_table.sql` | Creates `notification_log` with FK → obligations ON DELETE CASCADE | No impact on obligations table |
| V7 | `V7__add_pending_notification_status.sql` | Alters status CHECK on `notification_log` to add PENDING | No impact on obligations |

**No migration touches existing obligation rows.** V5 is the only migration that modifies the obligations table after creation, and it only adds a constraint — rows with NULL or positive values pass. No `ALTER TABLE obligations DROP COLUMN`, `DEFAULT`, or data-modifying DML exists.

**Flyway configuration** (`src/main/resources/application.properties`):

```properties
spring.jpa.hibernate.ddl-auto=none          # Hibernate cannot touch the schema
spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true      # Required for Supabase (non-empty public schema)
spring.flyway.baseline-version=0            # Ensures V1 executes (baseline does not skip it)
```

`ddl-auto=none` is the critical safety net — even if an entity annotation diverges from the SQL column definition, Hibernate will not silently alter the schema.

### Risk #3: Entity-to-column type mapping

`src/main/java/com/example/finance_hq/obligation/Obligation.java`

| Entity field | Java type | JPA annotation | SQL column | SQL type | Notes |
|---|---|---|---|---|---|
| `id` | `UUID` | `@Column(columnDefinition = "uuid")` | `id` | `UUID NOT NULL PK` | ✓ |
| `user` | `User` (ManyToOne) | `@JoinColumn(name="user_id", nullable=false)` | `user_id` | `UUID NOT NULL FK` | ✓ |
| `name` | `String` | `@Column(nullable=false)` | `name` | `VARCHAR(255) NOT NULL` | ✓ — no `@Size(max=255)` on entity, but DTO validates it |
| `amount` | `BigDecimal` | `@Column(nullable=false)` | `amount` | `NUMERIC(15,2) NOT NULL` | ✓ — no `@Column(precision=15,scale=2)` on entity; safe because `ddl-auto=none`; DTO validates `@Digits(integer=13,fraction=2)` |
| `category` | `ObligationCategory` | `@Enumerated(EnumType.STRING), @Column(nullable=false)` | `category` | `VARCHAR(20) NOT NULL CHECK(enum)` | ✓ — longest enum value is 9 chars (ESSENTIAL/IMPORTANT) |
| `period` | `ObligationPeriod` | `@Enumerated(EnumType.STRING), @Column(nullable=false)` | `period` | `VARCHAR(20) NOT NULL CHECK(enum)` | ✓ — FIXED_TERM is 10 chars |
| `paymentDay` | `Integer` | `@Column(name="payment_day", nullable=false)` | `payment_day` | `SMALLINT NOT NULL` | ✓ — Java Integer (INT4) maps to SMALLINT (INT2); safe for range 1–31; no truncation |
| `endDate` | `LocalDate` | `@Column(name="end_date")` | `end_date` | `DATE` (nullable) | ✓ |
| `remainingPayments` | `Integer` | `@Column(name="remaining_payments")` | `remaining_payments` | `INT` (nullable) | ✓ |
| `createdAt` | `LocalDateTime` | `@Column(name="created_at", nullable=false)` | `created_at` | `TIMESTAMP NOT NULL DEFAULT NOW()` | ✓ — `@PrePersist` sets it in Java, SQL DEFAULT is the fallback |

**One annotation gap (not a runtime risk):** `amount` has no `@Column(precision=15, scale=2)`. Because `ddl-auto=none`, Hibernate never generates DDL, so the actual column stays `NUMERIC(15,2)` as Flyway created it. If `ddl-auto=update` were ever switched on, Hibernate would generate `NUMERIC(19,2)` (its default for BigDecimal). Risk: low but worth documenting as a discipline gap.

**DTO validation mirrors DB constraints:**
- `CreateObligationRequest.amount`: `@Digits(integer=13, fraction=2)` matches NUMERIC(15,2)
- `CreateObligationRequest.paymentDay`: `@Min(1) @Max(31)` matches `CHECK (payment_day BETWEEN 1 AND 31)`
- `CreateObligationRequest.name`: `@Size(max=255)` matches VARCHAR(255)

**Service-level validation is defense-in-depth:** `ObligationService.create()` validates FIXED_TERM completeness before constructing the entity, so the SQL composite CHECK constraint is a secondary guard (`src/main/java/com/example/finance_hq/obligation/ObligationService.java:36-43`).

### Risk #3: What existing tests cover (and what they miss)

`src/test/java/com/example/finance_hq/obligation/ObligationControllerIntegrationTest.java` uses `@SpringBootTest @Import(TestcontainersConfiguration.class) @ActiveProfiles("test") @Transactional` — real Postgres 16-alpine, all migrations applied, each test rolled back automatically.

Covered:
- CRUD happy paths (status codes, presence of `id`, `nextDueDate`, `category`, `endDate`, `remainingPayments=6`)
- Validation (400 on missing FIXED_TERM fields, zero amount, paymentDay=32)
- Basic round-trip for RECURRING and FIXED_TERM

**Missing — the critical gap for Risk #3:**
1. **No full field-level assertion on POST → GET round-trip.** Existing tests assert only selected fields (`id`, `nextDueDate`, `category`, `endDate`, `remainingPayments`). No test asserts: `amount` = exactly `500.12` (2 decimal places preserved), `name` = exact string, `period` = exact enum, `paymentDay` = exact value, `createdAt` is non-null and sensible.
2. **No assertion that PATCH leaves unmodified fields intact.** `update_200_amountChanged` asserts only the updated `amount`. No test asserts `name`, `category`, `period`, `endDate`, `remainingPayments`, `createdAt` are unchanged after the PATCH.
3. **No explicit migration-chain integrity test.** Since Testcontainers starts fresh and runs V1–V7, this is implicitly covered every test run, but there is no test that seeds a row directly (bypassing service validation) and confirms the schema accepts it — which would catch any future migration that silently corrupts existing data.

### Risk #4: IDOR surface map

**All obligation-handling endpoints** (`src/main/java/com/example/finance_hq/obligation/ObligationController.java`):

| Endpoint | Obligation ID in path? | User passed to service? | Service method | Ownership enforced? |
|---|---|---|---|---|
| `GET /api/obligations` | No | `@AuthenticationPrincipal User user` → `service.findAll(user)` | `repository.findAllByUser(user, page)` | ✓ — scoped to user |
| `POST /api/obligations` | No | `@AuthenticationPrincipal User user` → `service.create(user, req)` | Entity created with `user` | ✓ — owned by user |
| `PATCH /api/obligations/{id}` | Yes | `@AuthenticationPrincipal User user` + `{id}` → `service.update(user, id, req)` | `repository.findByIdAndUser(id, user)` | ✓ — 404 if not found for user |
| `DELETE /api/obligations/{id}` | Yes | `@AuthenticationPrincipal User user` + `{id}` → `service.delete(user, id)` | `repository.findByIdAndUser(id, user)` | ✓ — 404 if not found for user |

No other controller accesses obligation IDs. `AuthController` handles auth only. `NotificationService` / `NotificationScheduler` are scheduler-triggered (no HTTP boundary).

**Repository analysis** (`src/main/java/com/example/finance_hq/obligation/ObligationRepository.java`):

| Method | User-scoped? | Used by |
|---|---|---|
| `findAllByUser(User user, Pageable pageable)` | ✓ | `ObligationService.findAll()` |
| `findByIdAndUser(UUID id, User user)` | ✓ | `ObligationService.update()`, `ObligationService.delete()` |
| `findAllWithUser()` (custom JPQL, no user filter) | ✗ intentional | `ObligationService.findAllSchedulerTargets()` — internal scheduler only, no HTTP exposure |
| `findById(UUID)` (inherited from JpaRepository) | ✗ | **Not called anywhere in production code** |

**Inherited `findById()` — the documented latent gap:**
- `ObligationRepository` extends `JpaRepository<Obligation, UUID>`, so `findById(UUID)` is available.
- It has no user ownership predicate.
- **It is not called anywhere in production controller or service paths.** Confirmed by grep across all service and controller files.
- Identified and consciously accepted in `context/archive/2026-05-28-add-and-list-obligations/reviews/impl-review-phase-1.md` (F2, MEDIUM severity). Decision: skip for MVP single-user scope.
- **Recommendation for test phase:** add a unit test or comment at the interface level documenting that `findById()` must not be used for user-facing lookups; this prevents a future developer from silently introducing an IDOR.

**SecurityConfig** (`src/main/java/com/example/finance_hq/security/SecurityConfig.java:40-51`): all obligation endpoints fall under `.anyRequest().authenticated()`. No obligation path is in `permitAll()`. JWT is validated by `JwtAuthenticationFilter` before any controller is reached.

**Exception contract** (`src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java:74-79`): `ObligationNotFoundException` → HTTP 404, body `{"title":"Not Found","detail":"Obligation not found"}`. Crucially, the same 404 is returned whether the obligation ID does not exist at all OR belongs to another user — no information leakage about other users' data.

### Risk #4: What existing tests cover (and what they miss)

Already covered in `ObligationControllerIntegrationTest`:
- `update_404_wrongUser` (line 250): PATCH with user B's token → 404 ✓
- `delete_404_wrongUser` (line 271): DELETE with user B's token → 404 ✓
- `list_200_doesNotReturnOtherUsersObligations` (line 290): GET list → empty for user B ✓
- `delete_404_notFound` (line 351): DELETE with random UUID → 404 ✓

**Missing — the one residual gap for Risk #4:**
- **`update_404_notFound`** — PATCH with a random UUID (not another user's, just nonexistent) → 404. This is the symmetric pair of `delete_404_notFound`. It is low-priority but completes the contract: the same 404 whether the ID never existed or belongs to another user, confirming the enumeration-prevention property.

There is **no GET `/api/obligations/{id}` endpoint**, so there is no individual-read IDOR surface to test. If this endpoint is added in the future, per-endpoint ownership test must be added at the same time (test plan §3 Risk #4 guidance).

---

## Code References

- `src/main/resources/db/migration/V4__create_obligations_table.sql:1-18` — canonical schema for obligations; source of truth for column types
- `src/main/resources/db/migration/V5__add_remaining_payments_check.sql:1-3` — additive CHECK constraint; proves no existing rows are corrupted
- `src/main/java/com/example/finance_hq/obligation/Obligation.java:26-48` — entity field declarations and JPA annotations
- `src/main/java/com/example/finance_hq/obligation/ObligationRepository.java:13-21` — all repository methods including the scoped and the unscoped (scheduler-only) ones
- `src/main/java/com/example/finance_hq/obligation/ObligationService.java:36-43` — FIXED_TERM validation before persist
- `src/main/java/com/example/finance_hq/obligation/ObligationService.java:57,67` — `findByIdAndUser()` is the only lookup used for HTTP paths; never bare `findById()`
- `src/main/java/com/example/finance_hq/obligation/ObligationController.java:25-51` — all four endpoints; all use `@AuthenticationPrincipal User user`
- `src/main/java/com/example/finance_hq/security/SecurityConfig.java:50` — `.anyRequest().authenticated()` catches all obligation routes
- `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java:74-79` — `ObligationNotFoundException` → HTTP 404
- `src/test/java/com/example/finance_hq/obligation/ObligationControllerIntegrationTest.java:250-306` — existing ownership boundary tests
- `src/test/java/com/example/finance_hq/TestcontainersConfiguration.java:14` — `postgres:16-alpine` — real Postgres, not H2
- `src/main/resources/application.properties` — `spring.jpa.hibernate.ddl-auto=none` (critical safety gate)

---

## Architecture Insights

**Why the IDOR protection is structurally sound:** The compound-key repository method `findByIdAndUser(UUID id, User user)` is the **only** path used for ID-based lookups in HTTP handlers. The controller always passes both the obligation ID and the authenticated `User` entity. There is no way to reach `findById(UUID)` from any current HTTP path; the only caller of the unscoped query (`findAllWithUser()`) is the internal scheduler method with no HTTP exposure.

**Why the data integrity picture is sound:** `ddl-auto=none` + Flyway-only DDL means schema state is deterministic — exactly what V1–V7 created, nothing more. All migrations after V4 are either new tables (V6), or additive constraints (V5), or constraint relaxation on a different table (V7). The only plausible "silent corruption" would come from a future migration adding a NOT NULL column with a wrong default to `obligations` — which the test suite (real Postgres) would catch immediately on the first insert after the migration.

**DTO validation as pre-DB guard:** `CreateObligationRequest` mirrors every SQL constraint in code: `@Digits(integer=13,fraction=2)` for NUMERIC(15,2), `@Min(1)/@Max(31)` for SMALLINT check, `@Size(max=255)` for VARCHAR(255). Constraint violations are caught at the controller layer (400 Bad Request) before they reach the DB.

---

## Historical Context (from prior changes)

**`context/archive/2026-05-28-add-and-list-obligations/reviews/impl-review-phase-1.md` (F2):**
Identified `findById()` IDOR risk — `JpaRepository` inherits an unscoped `findById()`. Decision: accepted as MVP risk because IDs are UUIDs (not sequential integers, so not easily guessable despite the review's note about BIGSERIAL). Recommended future fix: override `findById()` to throw `UnsupportedOperationException`.

**`context/archive/2026-05-28-add-and-list-obligations/reviews/impl-review.md` (F6):**
`GET /api/obligations` (list) had no ownership isolation test — `list_200_doesNotReturnOtherUsersObligations` was added as a fix. This test now exists and passes.

**`context/archive/2026-05-29-edit-and-delete-obligations/reviews/impl-review.md` (F2):**
V5 migration was added in response to a missing `remaining_payments > 0` CHECK. The migration is `ALTER TABLE ... ADD CONSTRAINT` — additive only, does not corrupt existing rows.

**`context/archive/2026-05-25-f-01-data-persistence-scaffold/plan.md`:**
`spring.jpa.hibernate.ddl-auto=none` was a deliberate architectural decision from the data persistence scaffold phase. Flyway is the sole DDL authority.

---

## Related Research

- `context/changes/testing-notification-pipeline-reliability/research.md` — Phase 1 research; established the integration test pattern (`@SpringBootTest @Import(TestcontainersConfiguration) @ActiveProfiles("test") @Transactional`) used as the template for Phase 2 tests.

---

## Test Oracle: What Each Test Must Prove

### Risk #3 tests

**Test A — Full FIXED_TERM field round-trip (new test needed)**  
Oracle source: V4 migration schema + PRD FR-003 ("all required fields"). Test seeding via POST, read-back via GET.

| Field | Expected value after round-trip | What failure means |
|---|---|---|
| `name` | Exact string seeded | Silent truncation or encoding error |
| `amount` | Exact BigDecimal with 2 decimal places (e.g., `500.12`) | NUMERIC(15,2) precision lost |
| `category` | Exact enum name (e.g., `"IMPORTANT"`) | Enum stored/read incorrectly |
| `period` | `"FIXED_TERM"` | Enum stored/read incorrectly |
| `paymentDay` | Integer `10` | Integer/SMALLINT coercion |
| `endDate` | Exact `LocalDate` (e.g., `2026-12-31`) | DATE ↔ LocalDate mapping error |
| `remainingPayments` | `6` | INT nullable mismatch |
| `createdAt` | Non-null, within test window | @PrePersist not firing |

**Test B — PATCH preserves unmodified fields (new test needed)**  
Oracle source: ObligationService.update() only calls `setAmount()` / `setPaymentDay()` — all other setters absent.

After PATCH with `{"amount": 999.99}` on a FIXED_TERM obligation, assert:
- `name`, `category`, `period`, `endDate`, `remainingPayments`, `createdAt` — unchanged
- `amount` — updated to `999.99`
- `paymentDay` — unchanged

### Risk #4 tests

**Test C — PATCH 404 for nonexistent ID (new test needed)**  
PATCH `/api/obligations/{random-uuid}` with valid JWT → 404. Symmetric to `delete_404_notFound` (already exists). Proves the same error contract regardless of whether the ID never existed or belongs to another user.

**Tests already passing (no new code needed):**
- `update_404_wrongUser` — PATCH with user B JWT → 404 ✓
- `delete_404_wrongUser` — DELETE with user B JWT → 404 ✓
- `list_200_doesNotReturnOtherUsersObligations` — GET list isolation ✓
- `delete_404_notFound` — DELETE nonexistent UUID → 404 ✓

---

## Open Questions

1. **`findById()` override:** The archive accepted this as MVP risk. Should Phase 2 include overriding the method to throw `UnsupportedOperationException` at the repository level, or is a code comment sufficient? The test plan guidance says tests should be added "per-endpoint" — this is a code-level defence, not a test. Recommend: add `@Override default <S extends Obligation> S findById(UUID id) { throw new UnsupportedOperationException(...); }` to `ObligationRepository` as part of Phase 2, with a corresponding unit test.

2. **`amount` `@Column` annotation gap:** `Obligation.amount` has no `@Column(precision=15, scale=2)`. If `ddl-auto` is ever changed, Hibernate would generate NUMERIC(19,2) instead of NUMERIC(15,2). Recommend adding the annotation as a discipline fix, not a blocking concern for Phase 2 tests.

3. **No GET `/api/obligations/{id}` endpoint:** The test plan's Risk #4 oracle mentions "GET, PATCH, DELETE". Currently there is no GET by ID. If the endpoint is added, the ownership test must accompany it at the same commit. This should be a note in the plan.
