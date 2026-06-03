# Data Integrity and Access Control — Plan Brief

> Full plan: `context/changes/testing-data-integrity-access-control/plan.md`
> Research: `context/changes/testing-data-integrity-access-control/research.md`

## What & Why

Close the test gaps for Phase 2 of the test plan (Risks #3 and #4): prove that obligation data survives a Flyway-migrated schema without field-level corruption, and that every endpoint enforcing ownership does so correctly. An inherited `findById()` IDOR gap documented in impl-review-phase-1 (F2) is also closed with a hard guard at the repository level.

## Starting Point

`ObligationControllerIntegrationTest` already has 15 tests covering CRUD, validation, and three of the four IDOR ownership scenarios (PATCH wrong user, DELETE wrong user, GET list isolation). No test asserts field-level round-trip correctness (amount precision, FIXED_TERM nullable fields, PATCH preserving unmodified fields). `ObligationRepository` inherits `findById(UUID)` from JpaRepository with no ownership predicate — not currently called in any controller path, but available to any future developer.

## Desired End State

Three new integration tests prove the data integrity contract: a FIXED_TERM obligation seeded directly via the repository is returned by the API with all fields intact (amount to 2 decimal places, paymentDay=31, endDate, remainingPayments); a PATCH updates only the targeted field and leaves createdAt unchanged at the DB level. One supplemental test (`update_404_notFound`) completes the IDOR boundary symmetry. The inherited `findById()` throws `UnsupportedOperationException` at the call site.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| findById() gap | Override + unit test | Hard guard prevents accidental bypass; 2 lines, zero current callers to break | Plan |
| Risk #3 test location | New class `ObligationDataIntegrityTest` | Separates field-integrity concerns from CRUD/validation/ownership in the existing class | Plan |
| Assertion approach | MockMvc JSON + repository for createdAt | Full-stack assertion (Jackson precision) + DB-level proof that PATCH doesn't reset the timestamp | Plan |
| Migration scenario | Seed via repository (bypass service) | Simulates pre-deploy data surviving the schema; tests the schema contract, not service validation | Plan |
| Cookbook | Fill §6.3 | Consistent with Phase 1 filling §6.1/§6.2; gives future endpoint authors the IDOR test template | Plan |

## Scope

**In scope:**
- `ObligationRepository.findById()` override throwing `UnsupportedOperationException`
- `ObligationDataIntegrityTest`: field round-trip, PATCH field-preservation, findById guard
- `ObligationControllerIntegrationTest`: add `update_404_notFound`
- `test-plan.md §6.3` cookbook entry
- Phase 2 status sync (`test-plan.md §3`, `change.md`)

**Out of scope:**
- Adding `@Column(precision=15, scale=2)` to `Obligation.amount` (discipline fix, not blocking)
- GET `/api/obligations/{id}` endpoint (doesn't exist; IDOR test must accompany if added)
- Testing Flyway migration SQL (DDL-only migrations; integration tests cover schema implicitly)
- `findAllWithUser()` (scheduler-internal, intentionally unscoped)

## Architecture / Approach

No new infrastructure. The test classes follow the exact `@SpringBootTest @Import(TestcontainersConfiguration.class) @ActiveProfiles("test") @Transactional` pattern from Phase 1. The `findById()` override is a `default` method in the repository interface — Spring Data JPA's proxy respects it and throws at the call site. Field assertions use `BigDecimal.compareTo()` (not `doubleValue()`) to catch precision loss in NUMERIC(15,2) round-trips.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. findById() Guard | Repository-level IDOR guard in production code | No current callers — test suite passing proves safety |
| 2. Integration Tests | Risk #3 round-trip + PATCH preservation + Risk #4 symmetry | createdAt comparison may need `isEqualToIgnoringNanos()` depending on TIMESTAMP precision |
| 3. Cookbook + Sync | §6.3 filled; Phase 2 marked complete | None — documentation only |

**Prerequisites:** Phase 1 complete before Phase 2 (Test 3 exercises the Phase 1 guard)  
**Estimated effort:** ~1 session across 3 phases

## Open Risks & Assumptions

- `LocalDateTime` comparison in Test 2 may require `isEqualToIgnoringNanos()` if Postgres TIMESTAMP truncates sub-millisecond precision differently than Java's `LocalDateTime.now()`
- The `default` method override approach assumes Spring Data JPA's proxy invokes interface default methods — this is standard JDK behavior and has worked in Spring Data since 1.10; if a test failure reveals otherwise, fallback is a custom repository fragment

## Success Criteria (Summary)

- All three `ObligationDataIntegrityTest` tests pass, including `fixedTerm_allFieldsRoundTripWithoutTruncation` with `BigDecimal.compareTo()` as the amount assertion
- `update_404_notFound` passes, completing the 404-contract symmetry for PATCH
- `test-plan.md §6.3` contains actionable guidance (not TBD)
