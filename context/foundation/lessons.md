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

---

## Run local backend with `--spring.profiles.active=local`

- **Context**: any local backend run
- **Problem**: without profile selected to local, app tries default — which has secrets configured in Supabase
- **Rule**: Always run the app locally with `--spring.profiles.active=local` (or `SPRING_PROFILES_ACTIVE=local`) so Spring Boot loads `/application-local.yml` from the project root, which provides `JWT_SECRET` and local datasource overrides.
- **Applies to**: implement, impl-review

---

## localStorage token storage — XSS exposure before httpOnly-cookie hardening

- **Context**: src/main/frontend/src/app/core/auth/token-storage.service.ts
- **Problem**: Access and refresh tokens stored in localStorage are readable by any JS on the page. Refresh token theft gives durable session access. Accepted risk for v0.1; httpOnly-cookie hardening deferred to v1.1.
- **Rule**: When tokens are stored in localStorage, add a Content Security Policy (CSP) header on Spring Boot responses as a compensating control before shipping to production.
- **Applies to**: Any feature that writes auth tokens to browser storage before cookie hardening is implemented.

---

## Spring Boot 4.x: PathRequest.toStaticResources() removed

- **Context**: src/main/java/com/example/finance_hq/security/SecurityConfig.java
- **Problem**: `PathRequest.toStaticResources().atCommonLocations()` (from `org.springframework.boot.autoconfigure.security.servlet.PathRequest`) does not exist in Spring Boot 4.x — the class was removed. Using it causes a compile-time `package does not exist` error.
- **Rule**: Use explicit ant patterns in `requestMatchers` instead: `"/*.js", "/*.css", "/*.ico", "/*.png", "/*.svg", "/*.woff", "/*.woff2", "/*.ttf", "/assets/**"`. This is stricter (only named extensions) and has no Spring Boot version dependency.
- **Applies to**: plan, implement — any phase that configures Spring Security static asset rules.

---

## Auth logout endpoint should require authentication post-MVP

- **Context**: src/main/java/com/example/finance_hq/auth/AuthController.java — POST /auth/logout is in the permitAll list
- **Problem**: An attacker who intercepts a refresh token can call /auth/logout to revoke it before the legitimate user does, silently logging them out. Accepted MVP risk for a single-user tool.
- **Rule**: Post-MVP, require a valid Bearer token on the logout endpoint and verify that the submitted refresh token belongs to the authenticated user before deleting it.
- **Applies to**: plan, implement — any phase that adds or modifies the logout flow or SecurityConfig permitAll rules.

---

## Always place private methods below public methods

- **Context**: All Java classes in this project
- **Problem**: Reader scans private helpers first, missing the public contract at a glance — makes the class harder to navigate and understand intent before seeing the implementation details.
- **Rule**: Always place private methods below public methods. Public methods form the readable contract; private helpers are implementation detail.
- **Applies to**: implement, impl-review

---

## Extract API path strings to named constants

- **Context**: All test classes and service/client classes that reference API paths
- **Problem**: Path typos go undetected; a renamed endpoint silently breaks only the tests that inline the old string — no compile-time safety.
- **Rule**: Extract all API path strings to named constants and reference only the constant. Inline path literals are prohibited; a single rename of the constant catches every usage.
- **Applies to**: implement, impl-review

---

## Annotate @SpringBootTest integration tests with @Transactional

- **Context**: All @SpringBootTest integration tests that write to the database
- **Problem**: Test data accumulates across runs; unique-constraint violations appear on re-run; tests depend on insertion order or leftover state from prior tests.
- **Rule**: Annotate every @SpringBootTest integration test class with @Transactional. Spring rolls back each test's writes automatically, preventing data accumulation and unique-constraint failures on re-run. When a test asserts on a DB unique-constraint violation (e.g. duplicate email → 409), the service must use saveAndFlush() rather than save() — otherwise Hibernate defers the INSERT until after MockMvc returns the response, the constraint is never checked, and the test unexpectedly gets 201.
- **Applies to**: implement, impl-review
