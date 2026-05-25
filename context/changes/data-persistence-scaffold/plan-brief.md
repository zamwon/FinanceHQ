# Data Persistence Scaffold — Plan Brief

> Full plan: `context/changes/data-persistence-scaffold/plan.md`

## What & Why

Wire the full persistence stack (Spring Data JPA + PostgreSQL driver + Flyway) from scratch and create the first database migration. F-01 is the prerequisite for every feature above the data layer — auth (F-02) and obligation storage (S-02) cannot start until a working datasource and schema migration tool are in place.

## Starting Point

The project is a bare Spring Boot 4.0.6 skeleton: `pom.xml` has no persistence dependencies, `application.properties` has no datasource config, and the only test does a context load that will break as soon as a datasource dep lands without matching config. Dockerfile, Railway, and CI are all operational.

## Desired End State

`./mvnw test` passes — Testcontainers spins a real PostgreSQL 16 container, Flyway applies V1, Spring context loads. A Railway deploy shows Flyway migration success in logs and `/actuator/health` returns HTTP 200 against live Supabase.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
|---|---|---|
| Migration tool | Flyway (SQL-based) | Spring Boot auto-configures it; SQL migrations are simpler than XML/YAML for a solo dev |
| Test DB strategy | Testcontainers + `@ServiceConnection` | CLAUDE.md forbids H2; Testcontainers gives prod-equivalent PostgreSQL with zero manual setup |
| JPA scope | Add Spring Data JPA now | F-02 needs JPA immediately — adding it in F-01 avoids a second `pom.xml` PR |
| User table PK | `BIGSERIAL` (auto-increment) | Simpler FK references at single-user MVP scale; UUID migration can follow later if needed |
| Entity code | Migration only — `@Entity` in F-02 | F-02's Spring Security contract may reshape the entity; keeping entity code out of F-01 avoids premature coupling |
| Verification gate | Include Railway deploy | Confirm Supabase Session Pooler wiring works in the live environment before F-02 begins |

## Scope

**In scope:**
- `pom.xml`: `spring-boot-starter-data-jpa`, `postgresql` (runtime), `flyway-core`, `flyway-database-postgresql`
- `pom.xml` test: `spring-boot-testcontainers`, `testcontainers/postgresql`
- `application.properties`: datasource env-var bindings, HikariCP pool size = 5, `ddl-auto=none`
- `src/main/resources/db/migration/V1__create_users_table.sql`
- `FinanceHqApplicationTests.java`: Testcontainers + `@ServiceConnection`
- Railway Variables: 3 datasource env vars set, deploy verified

**Out of scope:**
- `User` `@Entity`, `UserRepository` — F-02 owns entity code
- V2+ migrations — one table, one migration
- Local Postgres dev setup — tests use Testcontainers; prod uses Supabase
- Flyway undo scripts

## Architecture / Approach

All persistence dependencies land together (they are tightly coupled). `application.properties` binds datasource credentials to Railway env vars — no hardcoded values anywhere. Flyway auto-discovers `db/migration/` from the classpath and runs on startup. Testcontainers overrides datasource config at test time via `@ServiceConnection`, requiring no local Postgres. The Railway deployment gate confirms Supabase connectivity using the Session Pooler URL (required because Railway is IPv4-only; Supabase's direct connection host resolves to IPv6).

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Wire Persistence Stack | `pom.xml` + `application.properties` + `V1__create_users_table.sql` | `flyway-database-postgresql` must be present alongside `flyway-core` (Flyway 10+ requirement) |
| 2. Testcontainers Test Config | `./mvnw test` passes with real Postgres; Flyway migration verified locally | Docker must be running on the dev machine |
| 3. Railway Deployment Verification | Live Supabase connected; `/actuator/health` returns 200 | Session Pooler URL format and `jdbc:` prefix must be exact |

**Prerequisites:** Docker running locally (for Testcontainers); Railway account with project linked; Supabase project with Session Pooler connection string available  
**Estimated effort:** ~1 session (1-2 hours across 3 short phases)

## Open Risks & Assumptions

- Supabase Session Pooler host format must be exact: `[ref].pooler.supabase.com:5432` — the direct host will produce a silent "Connection refused" on Railway due to IPv6/IPv4 mismatch
- `SPRING_DATASOURCE_URL` must include `jdbc:postgresql://` prefix — Railway's auto-injected `DATABASE_URL` is not transformed automatically
- `flyway-database-postgresql` version is managed by Spring Boot BOM — no explicit version needed, but if resolution fails, check that Boot 4.0.6 BOM includes a Flyway 10.x entry

## Success Criteria (Summary)

- `./mvnw test` exits 0 with Flyway migration log visible
- Railway deploy shows `Started FinanceHqApplication` + `Successfully applied 1 migration`
- `/actuator/health` returns HTTP 200 against live Supabase
