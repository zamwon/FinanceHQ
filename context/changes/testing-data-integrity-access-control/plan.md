# Data Integrity and Access Control — Implementation Plan

## Overview

Close the test gaps from `context/foundation/test-plan.md` Phase 2 (Risks #3 and #4). One code change adds an ownership guard at the repository level; three integration tests prove field-level data integrity; one supplemental test completes the IDOR boundary coverage; and a cookbook entry locks in the pattern for future endpoints.

## Current State Analysis

Risk #3 (data integrity) is partially covered: `ObligationControllerIntegrationTest` proves CRUD round-trips succeed at the status-code level but does not assert exact field values. No test confirms `amount` preserves 2 decimal places, that `endDate`/`remainingPayments` survive the full stack, or that a PATCH leaves unmodified fields intact.

Risk #4 (IDOR) is largely covered: `update_404_wrongUser`, `delete_404_wrongUser`, and `list_200_doesNotReturnOtherUsersObligations` already pass. One gap remains — `update_404_notFound` (PATCH with a nonexistent UUID) is missing, making the 404-response contract asymmetric with the DELETE path. A second gap is structural: `JpaRepository.findById(UUID)` is inherited by `ObligationRepository` with no user ownership predicate and no guard against accidental use.

The existing test infrastructure is fully wired: `TestcontainersConfiguration` (Postgres 16-alpine, `@ServiceConnection`), `@ActiveProfiles("test")` with JWT config in `application-test.properties`, and the `@SpringBootTest @Import(TestcontainersConfiguration.class) @Transactional` class pattern from `NotificationServiceIntegrationTest`.

## Desired End State

After this plan:
- `ObligationRepository.findById()` throws `UnsupportedOperationException` if called — any future endpoint that accidentally bypasses ownership is caught at the call site.
- A new `ObligationDataIntegrityTest` class proves: all FIXED_TERM fields (including `amount` to 2 decimal places, `endDate`, `remainingPayments`, `paymentDay=31`) survive both the Flyway-migrated schema and Jackson serialization without truncation or coercion; and a PATCH leaves all unmodified fields — including `createdAt` — unchanged at the DB level.
- `ObligationControllerIntegrationTest` has `update_404_notFound` completing the 404-contract symmetry with `delete_404_notFound`.
- Cookbook §6.3 is filled with the per-endpoint IDOR boundary test pattern.
- `test-plan.md §3` Phase 2 reads `complete`.

### Key Discoveries

- `ObligationRepository` extends `JpaRepository<Obligation, UUID>` — `findById(UUID)` is inherited with no ownership predicate; not currently called in any production path but available — `src/main/java/com/example/finance_hq/obligation/ObligationRepository.java:13`
- `ObligationService.update()` and `.delete()` exclusively use `findByIdAndUser(id, user)` — `src/main/java/com/example/finance_hq/obligation/ObligationService.java:57,67`
- `Obligation.amount` maps to `NUMERIC(15,2)` in V4 migration; `@Column` on the entity has no `precision`/`scale` annotations — `src/main/java/com/example/finance_hq/obligation/Obligation.java:26-28`; `src/main/resources/db/migration/V4__create_obligations_table.sql:5`
- `Obligation.paymentDay` maps to `SMALLINT` in DB; entity declares `Integer` — safe for 1–31, no truncation — `V4__create_obligations_table.sql:8`; `Obligation.java:38-39`
- `ObligationService.update()` calls only `setAmount()` / `setPaymentDay()` — no other setters are exposed or called — `ObligationService.java:59-60`
- `GlobalExceptionHandler` maps `ObligationNotFoundException` to 404 with the same response body regardless of whether the ID doesn't exist or belongs to another user — `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java:74-79`
- Existing test `delete_404_notFound` (line 351 in `ObligationControllerIntegrationTest`) is the structural twin of the missing `update_404_notFound`

## What We're NOT Doing

- Not adding a GET `/api/obligations/{id}` endpoint (not in scope); if added later, an ownership test must accompany it at the same commit
- Not testing the Flyway migration chain at the SQL level (all migrations run against real Postgres in every test run — the schema is a given)
- Not testing error responses from the override (the method is never expected to be called in production; the test proves the guard exists)
- Not adding `@Column(precision=15, scale=2)` to `Obligation.amount` (the annotation gap is harmless while `ddl-auto=none`; it is a discipline fix, not a correctness bug — deferred)
- Not modifying `findAllWithUser()` (scheduler-internal, no HTTP exposure, intentional unscoped query)

## Implementation Approach

Phase 1 closes the structural IDOR gap in production code before any tests depend on it. Phase 2 adds all new test methods in parallel (same class, same setup overhead). Phase 3 updates documentation and status.

---

## Phase 1: Code Fix — findById() Ownership Guard

### Overview

Add a `default` method override to `ObligationRepository` that throws `UnsupportedOperationException`. Any future production or test caller that accidentally uses the inherited `findById()` gets an immediate, descriptive failure at the call site rather than a silent data leak.

### Changes Required

#### 1. Override findById() in ObligationRepository

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationRepository.java`

**Intent**: Prevent the inherited `JpaRepository.findById(UUID)` from being called anywhere in the codebase. Spring Data JPA respects `default` interface methods — the proxy will invoke this override instead of generating a by-primary-key lookup.

**Contract**: Add the following default method to the interface body. The message must tell the caller exactly which method to use instead.

```java
@Override
default Optional<Obligation> findById(UUID id) {
    throw new UnsupportedOperationException(
        "Direct ID lookup bypasses ownership. Use findByIdAndUser(UUID, User) instead.");
}
```

### Success Criteria

#### Automated Verification

- `./mvnw test` passes with no regressions — confirms no current production or test path calls the now-guarded method

#### Manual Verification

- Read the override: message names `findByIdAndUser` as the correct replacement
- Confirm no existing test or production class is broken by the change (the test suite passing is the evidence)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes live in the `## Progress` section at the bottom of the plan.

---

## Phase 2: Integration Tests — Risk #3 + Risk #4

### Overview

New `ObligationDataIntegrityTest` class (field-level integrity, findById guard) and one new method in the existing `ObligationControllerIntegrationTest` (IDOR symmetry). All integration tests use the established `@SpringBootTest @Import(TestcontainersConfiguration.class) @ActiveProfiles("test") @Transactional` pattern.

### Changes Required

#### 1. New integration test class — ObligationDataIntegrityTest

**File**: `src/test/java/com/example/finance_hq/obligation/ObligationDataIntegrityTest.java`

**Intent**: Three tests that address the gaps research identified: full field round-trip for FIXED_TERM obligations, PATCH field-preservation proof, and repository-level guard verification.

**Contract**: Class-level annotations and injected beans:

```java
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class ObligationDataIntegrityTest {

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository userRepository;
    @Autowired ObligationRepository obligationRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }
    // same helper constants AUTH_REGISTER, AUTH_LOGIN, API_OBLIGATIONS and
    // private helpers registerAndLogin(), json(), parseBody(), parseBodyAsList()
    // as in ObligationControllerIntegrationTest — copy them verbatim
}
```

---

**Test 1**: `fixedTerm_allFieldsRoundTripWithoutTruncation`

**Intent**: Prove that a FIXED_TERM obligation seeded directly via the repository (bypassing service validation, simulating pre-deploy data) is returned by the API with every field intact — specifically `amount` to 2 decimal places, `paymentDay=31`, `endDate`, `remainingPayments`, `category`, `period`, and `name`.

**Contract**:
- Register + login user via MockMvc (`registerAndLogin("integrity_roundtrip@test.com", ...)`); retrieve the `User` entity via `userRepository.findByEmail("integrity_roundtrip@test.com").orElseThrow()`
- Seed obligation directly: `obligationRepository.save(new Obligation(user, "Loan Repayment", new BigDecimal("500.12"), ObligationCategory.IMPORTANT, ObligationPeriod.FIXED_TERM, 31, LocalDate.of(2027, 3, 31), 12))`
- GET `/api/obligations` with the token, parse the list, find the seeded obligation by name
- Assert each field against the seeded value:
  - `name` = `"Loan Repayment"`
  - `amount` parsed as `BigDecimal` = `new BigDecimal("500.12")` (use `new BigDecimal(body.get("amount").toString()).compareTo(new BigDecimal("500.12")) == 0`)
  - `category` = `"IMPORTANT"`
  - `period` = `"FIXED_TERM"`
  - `paymentDay` = `31`
  - `endDate` = `"2027-03-31"` (Jackson serializes `LocalDate` as ISO string via Spring Boot's default JavaTimeModule)
  - `remainingPayments` = `12`
  - `createdAt` is not null

---

**Test 2**: `patch_preservesAllUnmodifiedFields`

**Intent**: Prove that updating `amount` via PATCH does not alter any other field — including `name`, `category`, `period`, `paymentDay`, `endDate`, `remainingPayments`, and `createdAt`. The repository-level assertion for `createdAt` proves the DB column was not reset, not just that the API response round-trips correctly.

**Contract**:
- Register + login via MockMvc; retrieve `User` entity via `userRepository.findByEmail(...)`
- POST a FIXED_TERM obligation via MockMvc using `fixedTermBody()` (see `ObligationControllerIntegrationTest` for the helper — reuse the same payload); record the full response body as `before`
- PATCH `/api/obligations/{id}` with `{"amount": 1234.56}` — `id` extracted from `before`
- GET `/api/obligations`; find the obligation by `id`; record full response body as `after`
- Assert: `after.name == before.name`, `after.category == before.category`, `after.period == before.period`, `after.paymentDay == before.paymentDay`, `after.endDate == before.endDate`, `after.remainingPayments == before.remainingPayments`, `after.createdAt == before.createdAt`
- Assert `after.amount` = `1234.56` (the patched value)
- Repository-level createdAt assertion: `obligationRepository.findByIdAndUser(UUID.fromString(id), user).orElseThrow().getCreatedAt()` parsed equals `before.createdAt` — proves the DB column was not overwritten

---

**Test 3**: `findById_throwsUnsupportedOperationException`

**Intent**: Prove the Phase 1 guard is in place at the Spring-proxied repository level — not just at the interface level.

**Contract**:
- `assertThatThrownBy(() -> obligationRepository.findById(UUID.randomUUID())).isInstanceOf(UnsupportedOperationException.class)`
- No user or obligation setup needed

---

#### 2. Supplemental test in existing ObligationControllerIntegrationTest

**File**: `src/test/java/com/example/finance_hq/obligation/ObligationControllerIntegrationTest.java`

**Intent**: Add `update_404_notFound` — PATCH with a random nonexistent UUID returns the same 404 as PATCH with another user's UUID. This completes the contract symmetry with the existing `delete_404_notFound` test and proves the error response is identical whether the ID never existed or belongs to another user.

**Contract**: New `@Test` method under the `// ── Ownership ──` comment block:

```java
@Test
void update_404_notFound() throws Exception {
    String token = registerAndLogin("update_not_found@test.com", "Test1234!");
    mvc.perform(patch(API_OBLIGATIONS + "/" + UUID.randomUUID())
            .contentType(APPLICATION_JSON)
            .header("Authorization", "Bearer " + token)
            .content(json(Map.of("amount", 1.00))))
       .andExpect(status().isNotFound());
}
```

### Success Criteria

#### Automated Verification

- `./mvnw test -Dtest=ObligationDataIntegrityTest` passes all three tests
- `./mvnw test -Dtest=ObligationControllerIntegrationTest` passes including `update_404_notFound`
- `./mvnw test` passes (full suite — confirms Phase 1 override broke no existing path)

#### Manual Verification

- Test 1: confirm the `amount` assertion uses `BigDecimal.compareTo()`, not `==` or `doubleValue()`, to catch precision differences
- Test 2: confirm the repository-level `createdAt` assertion queries via `findByIdAndUser()` (not the now-guarded `findById()`), and that the parsed `LocalDateTime` comparison handles nanoseconds (use `isEqualToIgnoringNanos()` if needed)
- Test 3: confirm the test invokes the Spring-proxied repository bean (autowired), not a manual mock — the proxy is what validates the guard

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human before proceeding to the next phase.

---

## Phase 3: Cookbook §6.3 + Phase 2 Plan Sync

### Overview

Fill the TBD cookbook entry for IDOR boundary tests and advance the phase tracking in `test-plan.md` and `change.md`.

### Changes Required

#### 1. Fill §6.3 — IDOR boundary test pattern

**File**: `context/foundation/test-plan.md`

**Intent**: Replace `TBD — see §3 Phase 2` in §6.3 with a concise cookbook entry describing the two-user IDOR setup and the two key assertions (mutating endpoint returns 404 for wrong user; list endpoint returns empty for wrong user).

**Contract**: Prose description covering: (1) the two-user setup (`registerAndLogin` for user A and user B); (2) user A creates the obligation; (3) user B targets user A's ID — expects 404 for PATCH and DELETE; (4) the same 404 is returned for a nonexistent UUID, proving no enumeration leakage. Reference `ObligationControllerIntegrationTest` as the canonical example.

---

#### 2. Advance Phase 2 status in §3 Phased Rollout table

**File**: `context/foundation/test-plan.md`

**Intent**: Mark Phase 2 as `complete` in the Phased Rollout table and record the change folder.

**Contract**: Update the Phase 2 row: `Status` → `complete`, `Change folder` → `testing-data-integrity-access-control`.

---

#### 3. Update change.md status

**File**: `context/changes/testing-data-integrity-access-control/change.md`

**Intent**: Advance status from `preparing` to `implemented`.

**Contract**: Set `status: implemented` and `updated: <date of completion>`.

### Success Criteria

#### Automated Verification

- `./mvnw test` passes (final full suite)

#### Manual Verification

- `test-plan.md §6.3` contains actionable guidance (not `TBD`)
- `test-plan.md §3` Phase 2 row reads `complete`
- `change.md` status reads `implemented`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human.

---

## Testing Strategy

### Integration Tests

- Full field round-trip: FIXED_TERM obligation seeded via repository → all fields asserted via GET response (amount precision, paymentDay=31, endDate, remainingPayments)
- PATCH field-preservation: POST → PATCH amount → GET → assert unchanged fields including createdAt via API and repository
- findById guard: Spring-proxied repository → `assertThatThrownBy` → UnsupportedOperationException
- IDOR symmetry: PATCH nonexistent UUID → 404 (same response as wrong-user PATCH)

### Manual Testing Steps

1. Run `./mvnw test` — all tests green
2. Inspect Test 1: confirm `BigDecimal.compareTo()` assertion (not `doubleValue()`) — this is the precision-loss sentinel
3. Inspect Test 2: confirm `findByIdAndUser()` (not `findById()`) used for repository-level createdAt check
4. Inspect Test 3: confirm the autowired bean (Spring proxy) is under test, not a Mockito mock

## References

- Research: `context/changes/testing-data-integrity-access-control/research.md`
- V4 migration schema (obligation column types): `src/main/resources/db/migration/V4__create_obligations_table.sql`
- Phase 1 integration test pattern: `src/test/java/com/example/finance_hq/notification/NotificationServiceIntegrationTest.java`
- Existing ownership tests to mirror: `src/test/java/com/example/finance_hq/obligation/ObligationControllerIntegrationTest.java:250-306`
- findById override target: `src/main/java/com/example/finance_hq/obligation/ObligationRepository.java:13`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Code Fix — findById() Ownership Guard

#### Automated

- [x] 1.1 `./mvnw test` passes with no regressions after findById() override

#### Manual

- [x] 1.2 Override message names `findByIdAndUser` as the correct replacement
- [x] 1.3 No existing test or production class broken (test suite passing is the evidence)

### Phase 2: Integration Tests — Risk #3 + Risk #4

#### Automated

- [ ] 2.1 `./mvnw test -Dtest=ObligationDataIntegrityTest` passes all three tests
- [ ] 2.2 `./mvnw test -Dtest=ObligationControllerIntegrationTest` passes including `update_404_notFound`
- [ ] 2.3 `./mvnw test` passes (full suite)

#### Manual

- [ ] 2.4 Test 1 amount assertion uses `BigDecimal.compareTo()` (not `doubleValue()`)
- [ ] 2.5 Test 2 repository-level createdAt check uses `findByIdAndUser()` (not `findById()`)
- [ ] 2.6 Test 3 invokes the Spring-proxied repository bean (autowired, not mocked)

### Phase 3: Cookbook §6.3 + Phase 2 Plan Sync

#### Automated

- [ ] 3.1 `./mvnw test` passes (final full suite)

#### Manual

- [ ] 3.2 `test-plan.md §6.3` contains actionable guidance (not TBD)
- [ ] 3.3 `test-plan.md §3` Phase 2 row reads `complete`
- [ ] 3.4 `change.md` status reads `implemented`
