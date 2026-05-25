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

## Supabase + Railway: use Session Pooler URL, not direct connection host

Railway is IPv4-only. Supabase's direct connection host resolves to IPv6 and produces a silent "Connection refused" at startup. Always use the **Session Pooler** host (`[ref].pooler.supabase.com:5432`) in `SPRING_DATASOURCE_URL`.

Also: Railway does not auto-transform `DATABASE_URL` for external Supabase connections. Set `SPRING_DATASOURCE_URL` manually with the `jdbc:postgresql://` prefix.
