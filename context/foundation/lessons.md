# Lessons

Recurring rules and pitfalls accepted by the team. Each entry must be internalized before starting any implementation phase.

---

## Spring Boot 4.x: Flyway auto-configuration requires `spring-boot-flyway` module

Spring Boot 4.x moved Flyway auto-configuration out of `spring-boot-autoconfigure` into a separate `spring-boot-flyway` module. Without it, `FlywayMigrationInitializer` is never created, migrations never run, and `spring.flyway.*` properties show IDE warnings ("Cannot resolve configuration property"). The symptom is a healthy deploy with no `flyway_schema_history` table.

**Always add to `pom.xml` alongside `flyway-core` and `flyway-database-postgresql`:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-flyway</artifactId>
</dependency>
```

---

## Flyway + Supabase: baseline at v0 to handle non-empty public schema

Supabase's `public` schema contains built-in objects (functions, types, etc.) before any migrations run. Flyway treats this as a non-empty schema and refuses to migrate without a history table, throwing:
`Found non-empty schema(s) "public" but no schema history table.`

**Fix in `application.properties`:**
```properties
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=0
```

`baseline-version=0` is critical — the default is `1`, which would skip V1 and never create the `users` table.

---

## Spring Boot 4.x: Testcontainers 2.x artifact names changed

Spring Boot 4.x bundles Testcontainers 2.x. TC 2.x renamed artifacts:
- `org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`
- `org.testcontainers:junit-jupiter` → `org.testcontainers:testcontainers-junit-jupiter`

Using TC 1.x artifact names with Spring Boot 4.x produces `Could not find artifact` errors at compile time.

---

## REST API error responses: use RFC 7807 Problem Details (tech debt: MVP uses simple JSON)

MVP auth endpoints return `{ "error": "message" }` for simplicity. This is **not** RFC 7807 compliant. Before adding more API surface (S-02, S-03), migrate error responses to RFC 7807 `ProblemDetail` (supported natively by Spring MVC 6+ via `ResponseEntityExceptionHandler`):

```java
ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email already registered");
problem.setTitle("Registration failed");
return ResponseEntity.status(409).body(problem);
```

This ensures Angular clients (and any future consumers) can rely on a standard error contract.

---

## Use fallback defaults in application.properties for local dev

- **Context**: Any phase that configures `application.properties` with environment variable placeholders (e.g. `${SPRING_DATASOURCE_URL}`)
- **Problem**: When env vars are not set locally (Railway injects them only in prod), Spring Boot fails to start with `HikariCP: 'url' must start with "jdbc"`. Profile-based workarounds (`application-local.properties`) are unreliable — Spring Boot DevTools restart launcher can lose the active profile, leaving placeholders unresolved.
- **Rule**: Always add a local fallback default using the `${VAR:default}` syntax in `application.properties` for every env var that Railway injects in prod. Local Postgres defaults are safe to commit since Railway env vars override them in prod.
- **Applies to**: plan, implement

---

## Supabase + Railway: use Session Pooler URL, not direct connection host

Railway is IPv4-only. Supabase's direct connection host resolves to IPv6 and produces a silent "Connection refused" at startup. Always use the **Session Pooler** host (`[ref].pooler.supabase.com:5432`) in `SPRING_DATASOURCE_URL`.

Also: Railway does not auto-transform `DATABASE_URL` for external Supabase connections. Set `SPRING_DATASOURCE_URL` manually with the `jdbc:postgresql://` prefix.

---

## JPA entity should not implement Spring Security UserDetails directly

- **Context**: src/main/java/com/example/finance_hq/user/User.java — User entity implements UserDetails
- **Problem**: Mixing the JPA entity with Spring Security's `UserDetails` contract couples persistence to the auth layer. Adding future security flags (locked, enabled, credentials_expired) requires schema changes on the core entity, and every entity fetch leaks the password hash through `getPassword()`.
- **Rule**: Keep `UserDetails` adapters in `auth/` or `security/`. Wrap the entity with a `UserPrincipal` (or equivalent) instead of having the entity itself implement `UserDetails`.
- **Applies to**: plan, implement
