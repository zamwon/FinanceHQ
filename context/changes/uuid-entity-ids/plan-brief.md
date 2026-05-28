# UUID Entity IDs — Plan Brief

> Full plan: `context/changes/uuid-entity-ids/plan.md`

## What & Why

Migrate all three entity primary keys from `BIGSERIAL`/`Long` to PostgreSQL's native `uuid` type. Sequential integer IDs are guessable — a review of Phase 1 of `add-and-list-obligations` surfaced this as a risk. UUID IDs eliminate the IDOR exposure before any obligation endpoints go live.

## Starting Point

Three entities (`User`, `RefreshToken`, `Obligation`) use `GenerationType.IDENTITY` with `Long id`. Three Flyway migrations define `BIGSERIAL` PKs and `BIGINT` FK columns. No REST endpoints yet expose obligation IDs in path variables, making this the right moment to migrate before those endpoints are written.

## Desired End State

All primary key and FK columns use the PostgreSQL `uuid` type. All JPA entities use `UUID id` with `GenerationType.UUID`. Hibernate auto-assigns UUIDs before each INSERT. The full test suite passes. The `add-and-list-obligations` change resumes at Phase 2 with UUID-aware types from the start.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| UUID scope | All 3 entities (User, RefreshToken, Obligation) | Consistent identity across all tables; no mixed ID types to explain | Plan |
| PostgreSQL column type | Native `uuid` (not VARCHAR) | 16-byte storage, proper index efficiency, Hibernate 7 JDBC driver maps it natively | Plan |
| Migration approach | Edit V1/V2/V4 in-place + local DB reset | No production data; clean schema history; no verbose ALTER TABLE boilerplate | Plan |
| Hibernate strategy | `GenerationType.UUID` (JPA 3.2) | Built into Hibernate 7.x / Spring Boot 4.x; no legacy `@GenericGenerator` needed | Plan |
| Sequencing | UUID first, then add-and-list-obligations Phase 2+ | Avoids retroactive Long→UUID fixes in controller + integration tests | Plan |
| Frontend | No changes | `id: string` already in use; UUID is wire-compatible | Plan |

## Scope

**In scope:**
- V1, V2, V4 Flyway migrations — in-place column type edits
- User, RefreshToken, Obligation entities — `UUID id` + `GenerationType.UUID`
- UserRepository, RefreshTokenRepository, ObligationRepository — type parameter + `findByIdAndUser` signature
- add-and-list-obligations plan Phase 4 — remove incorrect `id: number` frontend change

**Out of scope:**
- Frontend changes (already `id: string`)
- JWT changes (subject is email)
- DTO changes (no IDs exposed yet)
- AuthController changes (no ID path variables)
- New ALTER migrations (in-place edits instead)

## Architecture / Approach

Hibernate 7.x (`GenerationType.UUID`) generates a UUIDv7 (time-ordered) before the INSERT — no database trigger or `gen_random_uuid()` needed. The PostgreSQL JDBC driver maps `java.util.UUID` ↔ `uuid` natively. `@Column(columnDefinition = "uuid")` makes this explicit. All FK columns referencing a UUID PK must also be `UUID NOT NULL`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Migrations + Entities | Bootable app with UUID schema and JPA layer aligned | Flyway checksum mismatch on local DB if reset is skipped |
| 2. Repositories + Coordination | Compilable queries; sibling plan updated | Full test suite must pass to confirm auth still works |

**Prerequisites:** No production data exists; local dev DB will be dropped and recreated once.  
**Estimated effort:** ~1 session; ~15 file edits, all mechanical (find-and-replace pattern).

## Open Risks & Assumptions

- Hibernates's UUIDv7 generation is time-based and monotonically increasing — good for index performance, but exposes rough creation timing from the ID. Acceptable for this single-user MVP.
- `RefreshToken.id` is internal-only (never in an API response) — could stay `Long`, but consistency was chosen over minimal blast radius.

## Success Criteria (Summary)

- `./mvnw test` passes after all changes — including `AuthControllerIntegrationTest` against a real Testcontainers PostgreSQL DB.
- Local backend starts cleanly after DB reset; `psql` shows `uuid` column types on all three tables.
- `add-and-list-obligations` Phase 2 can be implemented with `UUID` types throughout without further migration work.
