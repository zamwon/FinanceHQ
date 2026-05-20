# Repository Guidelines

FinanceHQ is a personal finance obligation tracker — Spring Boot 4.0.6 / Java 21 REST API with an Angular SPA in the same repo. Full requirements: @context/foundation/prd.md. Stack decisions: @context/foundation/tech-stack.md.

## Hard Rules

- **PostgreSQL only.** Do not add H2 or any in-memory database. All environments (dev, test, prod) use PostgreSQL.
- **Package root is `com.example.finance_hq`** (underscores, not hyphens). The artifact ID uses a hyphen (`finance-hq`) but Java package naming forbids hyphens — the bootstrapped package uses underscores.
- **Spring Security, JPA/Hibernate, and JavaMail are not yet in `pom.xml`.** Add them only when implementing auth, persistence, or notifications per @context/foundation/prd.md — do not add pre-emptively.

## Key Patterns

- **Controller -> mapToDto(request) -> Service**: Controllers always map requests to DTOs before calling service methods
- **Service -> DTO -> Controller**: Services always return DTOs (not entities) to controllers. Never return raw entities from service methods that are called by controllers
- **DTO -> Response mapping**: Controllers never construct Response objects directly from DTO fields. The Response class must contain a static factory method (e.g. `fromDto(dto)` or `mapToResponseDetail(dto)`) that encapsulates the mapping logic. The controller calls only that method: `return SomeResponse.fromDto(service.getSomething(id))`
- **Custom validation**: Via annotation + validator class pairs (e.g., `@IsStrongPassword` + `IsStrongPasswordValidator`)
- **Exception handling**: Centralized in `MainControllerAdvice`. Throw with `ResponseMessage` enum values: `throw new CustomEntityNotFoundException(Entity.class.getSimpleName(), NOT_FOUND.name())`; always log a `warn` before throwing
- **Logging**: Use Lombok's `@Slf4j` annotation. Always log a `warn` before throwing exceptions.

## Build, Test, and Development Commands

- `./mvnw spring-boot:run` — start backend dev server
- `./mvnw test` — run all tests
- `./mvnw clean package` — build deployable jar
- `./mvnw test -Dtest=ClassName#methodName` — run a single test
- Angular (from `src/main/frontend/`): `ng serve` — start frontend dev server

## Project Structure

```
src/main/java/com/example/finance_hq/  — Spring Boot source
src/main/resources/                    — application.properties, static assets
src/main/frontend/                     — Angular SPA (to be scaffolded)
src/test/java/com/example/finance_hq/ — JUnit tests
context/foundation/                    — PRD, tech-stack, shape notes
```

## Coding Style & Naming Conventions

- Java 21, Spring Boot 4.0.6. Classes: PascalCase. Package segments: snake_case under `com.example.finance_hq`.
- No static analysis configured yet (no Checkstyle, SpotBugs). Use `./mvnw test` as the sole automated validation gate.

## Testing Guidelines

- See @src/test/java/com/example/finance_hq/FinanceHqApplicationTests.java for the established pattern.
- Run `./mvnw test` before marking any task done.

## Commit & Pull Request Guidelines

- Branch names: `feature/<name>` or `fix/<name>`.
- No commit message convention established yet — repository has no history.

## Architecture Overview

Spring MVC serves a JSON REST API; Angular SPA (to be scaffolded at `src/main/frontend/`) consumes it. In production the Angular build is served as static assets by Spring Boot. See @CLAUDE.md for agent-specific workflow notes.
