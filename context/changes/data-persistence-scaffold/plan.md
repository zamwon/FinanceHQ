# Data Persistence Scaffold Implementation Plan

## Overview

Wire the full persistence stack in a single change: Spring Data JPA + PostgreSQL driver + Flyway in `pom.xml`, datasource config in `application.properties`, a V1 migration creating the `users` table, and Testcontainers replacing the bare `@SpringBootTest`. Closes with a Railway deploy verifying live Supabase connectivity via the Session Pooler URL. Unlocks F-02 (auth scaffold) and S-02 (obligation storage).

## Current State Analysis

The project has zero persistence infrastructure:
- `pom.xml` contains only `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`, `spring-boot-devtools`, and `spring-boot-starter-webmvc-test` — no JPA, no driver, no migration tool
- `application.properties` has no `spring.datasource.*` properties
- No entities, repositories, or migration files exist anywhere in the tree
- The only test (`FinanceHqApplicationTests`) does a bare `@SpringBootTest` context load with no datasource — it will fail the moment datasource dependencies land without matching test config
- Dockerfile (line 11-12) uses `ENV JAVA_OPTS="-Xmx384m -Xms128m"` with a shell-form `ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]` — heap limits are already managed; no Railway Variable override needed

### Key Discoveries:

- `pom.xml:33-53` — only 4 dependencies; all persistence deps must be added from scratch
- `src/main/resources/application.properties:1-4` — no datasource config; all properties must be added
- `Dockerfile:11-12` — `JAVA_OPTS` already wired via shell-form ENTRYPOINT; heap limits in place
- `railway.toml` — healthcheckPath `/actuator/health` already configured; deploy gate is /actuator/health HTTP 200
- `context/foundation/infrastructure.md` — fully specifies Railway + Supabase Session Pooler wiring, including critical env var format and HikariCP pool size

## Desired End State

After this plan:
- `./mvnw test` passes: Testcontainers spins a PostgreSQL 16 container, Flyway applies V1 migration, Spring context loads
- `./mvnw clean package -DskipTests` produces a deployable JAR with all persistence deps
- A `users` table exists in Supabase (created by Flyway) with `id`, `email`, `password_hash`, `created_at`
- Railway deploy shows Flyway migration success in logs and `/actuator/health` returns HTTP 200

### Key Discoveries:

- Spring Boot 4.x ships Flyway 10.x — `flyway-core` alone does not include PostgreSQL dialect; `flyway-database-postgresql` is a mandatory second artifact
- `SPRING_DATASOURCE_URL` must use `jdbc:postgresql://` prefix; Railway's auto-injected `DATABASE_URL` uses `postgresql://` (no `jdbc:`) and is not auto-transformed
- `spring.jpa.hibernate.ddl-auto` must be explicitly set to `none` — Flyway owns DDL; Hibernate must not attempt schema management
- HikariCP pool max-size should be 5 for MVP to avoid exhausting Supabase's session pooler connection limit

## What We're NOT Doing

- No `User` `@Entity` or `UserRepository` — F-02 (auth scaffold) owns entity code once Spring Security contract is defined
- No `V2` migrations for obligations or any other table — scope is one migration, one table
- No local dev Postgres setup — tests use Testcontainers; prod/staging use Supabase via Railway env vars
- No Flyway undo scripts — forward-only migrations at MVP scale
- No schema validation via `ddl-auto=validate` — no entities in F-01 to validate against

## Implementation Approach

Add all persistence dependencies together in Phase 1 (they are tightly coupled and always co-deployed), configure datasource via Railway env vars pattern, and create the single V1 migration. Phase 2 converts the existing test to Testcontainers so it provides a real verification signal rather than a vacuous context load. Phase 3 is the deployment gate — Railway + Supabase connectivity confirmed in the live environment before declaring F-01 done.

## Critical Implementation Details

**`flyway-database-postgresql` is mandatory in Flyway 10+.** Spring Boot 4.x manages Flyway 10.x. Without `flyway-database-postgresql` alongside `flyway-core`, Flyway throws `FlywayException: Unable to instantiate JDBC driver` for PostgreSQL at startup. Both artifacts must be present.

**`SPRING_DATASOURCE_URL` requires the `jdbc:` prefix and the Session Pooler host.** Railway auto-injects `DATABASE_URL` in `postgresql://` format only for its own Postgres add-on, not for external Supabase. For Supabase, manually set `SPRING_DATASOURCE_URL=jdbc:postgresql://[ref].pooler.supabase.com:5432/postgres` in Railway Variables. Use the Session Pooler host (`[ref].pooler.supabase.com`), not the direct connection host — Railway is IPv4-only; the direct host resolves to IPv6 and produces a silent "Connection refused" on startup.

**Do not set `JAVA_TOOL_OPTIONS` in Railway Variables.** The Dockerfile already manages JVM heap via `JAVA_OPTS` in a shell-form ENTRYPOINT. Setting `JAVA_TOOL_OPTIONS` in Railway Variables would double-apply heap limits.

---

## Phase 1: Wire Persistence Stack

### Overview

Add all four persistence dependencies to `pom.xml` and configure `application.properties` with datasource env-var bindings, HikariCP pool limit, and JPA settings. Create the V1 Flyway migration for the `users` table.

### Changes Required:

#### 1. Runtime persistence dependencies

**File**: `pom.xml`

**Intent**: Add the four dependencies that make up the persistence stack. All are required together — JPA needs a driver; Flyway needs both `flyway-core` and `flyway-database-postgresql` for PostgreSQL in Flyway 10+.

**Contract**: Add inside the `<dependencies>` block, after the existing `spring-boot-starter-actuator` entry:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

#### 2. Datasource and JPA configuration

**File**: `src/main/resources/application.properties`

**Intent**: Bind datasource credentials to Railway env vars, cap HikariCP pool to 5 connections (Supabase session pooler limit for MVP), and disable Hibernate DDL management so Flyway owns the schema exclusively.

**Contract**: Append to the existing four properties (do not remove or alter the existing `spring.application.name`, `server.port`, or `management.*` entries):

```properties
# Datasource — values injected via Railway Variables
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
spring.datasource.hikari.maximum-pool-size=5

# JPA — Flyway owns DDL; Hibernate must not touch schema
spring.jpa.hibernate.ddl-auto=none
spring.jpa.open-in-view=false
```

#### 3. V1 Flyway migration — users table

**File**: `src/main/resources/db/migration/V1__create_users_table.sql`

**Intent**: Create the `users` table that F-02 (auth scaffold) will map its `@Entity` against. This is the only schema change in F-01.

**Contract**: New file; create the `db/migration/` directory path. Contents:

```sql
CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  email         VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

### Success Criteria:

#### Automated Verification:

- `./mvnw clean package -DskipTests` exits 0 — all four deps resolve from Maven Central and the project compiles
- `./mvnw dependency:tree | grep javax` returns empty — no `javax.*` transitive dependencies that would cause `ClassNotFoundException` at Boot 4.x startup

#### Manual Verification:

- Migration file exists at `src/main/resources/db/migration/V1__create_users_table.sql`
- No datasource properties point to hardcoded credentials — all values use `${...}` env var binding

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding to Phase 2.

---

## Phase 2: Testcontainers Test Configuration

### Overview

Add Testcontainers dependencies (test scope) and update `FinanceHqApplicationTests` to spin a real PostgreSQL container via `@ServiceConnection`. The test must pass end-to-end: container starts, Flyway migrates, Spring context loads.

### Changes Required:

#### 1. Testcontainers test dependencies

**File**: `pom.xml`

**Intent**: Add `spring-boot-testcontainers` (Spring Boot 4.x `@ServiceConnection` integration) and `testcontainers/postgresql` (the PostgreSQL module) at test scope. These land alongside the runtime deps added in Phase 1.

**Contract**: Add inside `<dependencies>`, after the existing `spring-boot-starter-webmvc-test` entry:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

#### 2. Convert context load test to Testcontainers

**File**: `src/test/java/com/example/finance_hq/FinanceHqApplicationTests.java`

**Intent**: Replace the bare `@SpringBootTest` with a Testcontainers-backed test. `@ServiceConnection` auto-wires the container's JDBC URL into `spring.datasource.*`, overriding whatever env vars are (or aren't) set locally. Flyway will run migrations against the container on context startup.

**Contract**: Full replacement of the file. `@Testcontainers` + `@ServiceConnection` + static `PostgreSQLContainer` field. The existing `contextLoads()` test method stays. Add required imports for `@Container`, `@ServiceConnection`, `@Testcontainers`, and `PostgreSQLContainer`.

### Success Criteria:

#### Automated Verification:

- `./mvnw test` exits 0
- Test output contains `Successfully applied 1 migration to schema "public"` (Flyway log line confirming V1 ran inside the container)

#### Manual Verification:

- First test run pulls `postgres:16-alpine` image (~80MB); confirm Docker is running before executing
- No `SPRING_DATASOURCE_URL` env var is required locally — `@ServiceConnection` overrides datasource config entirely for tests

**Implementation Note**: After `./mvnw test` passes, pause for manual confirmation before proceeding to Phase 3 (Railway deployment).

---

## Phase 3: Railway Deployment Verification

### Overview

Set the three datasource env vars in Railway Variables using the Supabase Session Pooler connection details, deploy to Railway, and confirm the app starts with Flyway migration success and a live `/actuator/health` 200.

### Changes Required:

#### 1. Railway Variables — datasource credentials

**File**: Railway Variables tab (dashboard or `railway variable set`)

**Intent**: Provide the three datasource env vars that `application.properties` binds to. Must use the Supabase **Session Pooler** host, not the direct connection host, to avoid Railway's IPv4 / Supabase IPv6 mismatch.

**Contract**: Set these three variables before deploying. Find the Session Pooler host in Supabase dashboard → Project Settings → Database → Connection string → Session mode:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://[ref].pooler.supabase.com:5432/postgres
SPRING_DATASOURCE_USERNAME=postgres.[ref]
SPRING_DATASOURCE_PASSWORD=[your-supabase-db-password]
```

Do **not** set `JAVA_TOOL_OPTIONS` — heap limits are already in the Dockerfile via `JAVA_OPTS`.

#### 2. Deploy and verify logs

**Intent**: Trigger a Railway deploy (GitHub push auto-deploys via the existing CI workflow, or `railway up` for a manual trigger). Confirm the startup sequence in logs.

**Contract**: After deploy, `railway logs` should contain both:
- `Started FinanceHqApplication` (Spring Boot startup complete)
- `Successfully applied 1 migration to schema "public"` (Flyway V1 ran against Supabase)

### Success Criteria:

#### Manual Verification:

- Railway Variables tab shows all three `SPRING_DATASOURCE_*` vars set before deploy
- `railway logs` shows `Started FinanceHqApplication` with no datasource connection errors
- `railway logs` shows `Successfully applied 1 migration to schema "public"` (Flyway ran V1)
- `curl https://[your-app].up.railway.app/actuator/health` returns HTTP 200 with `{"status":"UP"}`
- Supabase dashboard → Table Editor shows the `users` table created in the `public` schema

---

## Testing Strategy

### Unit Tests:

- No domain logic in F-01 — context load is the full test coverage

### Integration Tests:

- `FinanceHqApplicationTests` via Testcontainers: starts real PostgreSQL 16, applies Flyway V1 migration, loads Spring context end-to-end

### Manual Testing Steps:

1. Confirm `./mvnw dependency:tree | grep javax` returns empty before deploying
2. Confirm `./mvnw test` passes locally with Docker running (Testcontainers)
3. Set Railway Variables using Session Pooler URL format
4. Push to `master` (or run `railway up`) to trigger deploy
5. Tail logs with `railway logs` and confirm both startup messages
6. Hit `/actuator/health` and verify `{"status":"UP"}`
7. Check Supabase Table Editor for `users` table

## Migration Notes

V1 is the first migration. Flyway checksums the file after applying — do not modify `V1__create_users_table.sql` after it runs in any environment. If the schema needs to change, create `V2__...`.

## References

- Infrastructure wiring: `context/foundation/infrastructure.md`
- Roadmap item: `context/foundation/roadmap.md` — F-01 (data-persistence-scaffold)
- Change identity: `context/changes/data-persistence-scaffold/change.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Wire Persistence Stack

#### Automated

- [x] 1.1 `./mvnw clean package -DskipTests` exits 0 — 6f580ac
- [x] 1.2 `./mvnw dependency:tree | grep javax` returns empty — 6f580ac

#### Manual

- [x] 1.3 Migration file exists at `src/main/resources/db/migration/V1__create_users_table.sql` — 6f580ac
- [x] 1.4 No datasource properties use hardcoded credentials — all values use `${...}` env var binding — 6f580ac

### Phase 2: Testcontainers Test Configuration

#### Automated

- [x] 2.1 `./mvnw test` exits 0
- [x] 2.2 Test output contains `Successfully applied 1 migration to schema "public"`

#### Manual

- [x] 2.3 Test runs without `SPRING_DATASOURCE_URL` set locally — `@ServiceConnection` overrides datasource config

### Phase 3: Railway Deployment Verification

#### Manual

- [ ] 3.1 Railway Variables set: `SPRING_DATASOURCE_URL` (Session Pooler host), `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- [ ] 3.2 `railway logs` shows `Started FinanceHqApplication` with no connection errors
- [ ] 3.3 `railway logs` shows `Successfully applied 1 migration to schema "public"`
- [ ] 3.4 `curl /actuator/health` returns HTTP 200 `{"status":"UP"}`
- [ ] 3.5 Supabase Table Editor shows `users` table in `public` schema
