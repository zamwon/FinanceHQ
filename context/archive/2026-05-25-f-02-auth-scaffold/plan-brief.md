# F-02 Auth Scaffold — Plan Brief

> Full plan: `context/changes/f-02-auth-scaffold/plan.md`

## What & Why

Wire JWT-based authentication over the F-01 persistence layer: four REST endpoints let a single user register, log in, refresh their tokens, and log out. Auth is the identity layer that every downstream slice (S-01, S-02, S-04) depends on — getting the token contract wrong here requires breaking refactors across all future work.

## Starting Point

F-01 is complete: `users` table (email, password_hash, id, created_at) exists in Supabase, Spring Data JPA is wired, and the Testcontainers `@ServiceConnection` test pattern is established. Zero auth code exists — greenfield implementation. Spring Security is not yet in `pom.xml`.

## Desired End State

`POST /auth/register` creates a BCrypt-hashed user. `POST /auth/login` returns a signed JWT access token (1h TTL) and a PostgreSQL-stored refresh token (30d TTL). `POST /auth/refresh` rotates both tokens atomically. `POST /auth/logout` revokes the refresh token from the DB. All other routes return 401 without a valid `Authorization: Bearer <token>` header.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Token type | JWT stateless, JJWT 0.12.x | Fits Railway's single-container deployment; natural pairing for the future Angular SPA REST client. | Plan |
| Refresh tokens | PostgreSQL `refresh_tokens` table | Enables true revocation and atomic rotation; consistent with the existing persistence layer. | Plan |
| Token expiry | 1h access + 30d refresh, rotated on use | Short access TTL limits breach window; rotation invalidates a stolen refresh token on next legitimate use. | Plan |
| Password rules | 8+ chars + uppercase + digit + special char | OWASP 2021 baseline with complexity — appropriate for a financial product. | Plan |
| Error format | Simple JSON `{ "error": "..." }` | MVP only; RFC 7807 migration path recorded in lessons.md. | Plan |
| CORS | localhost:4200 + `CORS_ALLOWED_ORIGINS` env var | Included in F-02 security config to unblock F-03 Angular scaffold immediately. | Plan |
| Logout | `POST /auth/logout` revokes refresh token from DB | Provides real session termination; access token expires naturally within 1h. | Plan |
| Filter reg. | `FilterRegistrationBean` with `setEnabled(false)` | Prevents Tomcat servlet double-registration when `@Component` filter is also added via `addFilterBefore`. | Plan |

## Scope

**In scope:** `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`; `JwtAuthenticationFilter` (OncePerRequestFilter); `SecurityFilterChain` (CSRF off, STATELESS); BCrypt password encoding; CORS config; V2 Flyway migration (`refresh_tokens` table); unit tests for `JwtService`; integration tests for all four endpoints.

**Out of scope:** OAuth/SSO, email verification, access token revocation (1h TTL is the mitigation), RFC 7807 errors, refresh token denylist, password reset, Angular wiring (F-03).

## Architecture / Approach

Spring Security 7.x lambda DSL with CSRF disabled and STATELESS session policy. `JwtAuthenticationFilter` validates the Bearer token on every authenticated request and populates the `SecurityContext`. `AuthService` (implements `UserDetailsService`) handles all four auth flows, delegating to `JwtService` (JJWT token operations) and `RefreshTokenService` (DB lifecycle). Package layout: `auth/` for service/controller/dto/exception, `security/` for filter and config, `user/` for entities and repositories.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Foundation | New deps, V2 migration, JWT/CORS properties, delete HelloController | Spring Security auto-config may change test behaviour before `SecurityFilterChain` is defined |
| 2. Core domain | `User` entity (implements `UserDetails`), `RefreshToken` entity, both repositories | Hibernate snake_case column name mismatch (e.g. `password_hash` → `passwordHash`) |
| 3. Token services | `JwtService` + `RefreshTokenService` | JJWT 0.12.x API breaking change from 0.11.x — `Jwts.parserBuilder()` removed |
| 4. Auth endpoints | DTOs with Bean Validation, `AuthService`, `AuthController`, `GlobalExceptionHandler` | Email enumeration via different 401 messages (mitigated: same message for unknown email and wrong password) |
| 5. Security config | `SecurityFilterChain`, `JwtAuthenticationFilter`, CORS beans | Filter double-registration if `FilterRegistrationBean` is omitted |
| 6. Tests | `JwtServiceTest` unit tests + `AuthControllerIntegrationTest` (12 cases) | JWT_SECRET must be set as test property source |

**Prerequisites:** F-01 complete (done); `JWT_SECRET` (Base64, 32+ bytes) must be set in Railway before any deploy that includes Phase 5 or later.
**Estimated effort:** ~3 sessions across 6 phases.

## Open Risks & Assumptions

- `JWT_SECRET` must be generated (`openssl rand -base64 32`) and added to Railway env vars before Phase 1 deploy
- Spring Boot 4.0.6 ships Spring Security 7.x; if the minor version produces any lambda DSL incompatibility, check Spring Security 7.x release notes
- `cors.allowed-origins` defaults to `http://localhost:4200` (no Railway var needed locally); production origin must be added before S-01 ships

## Success Criteria (Summary)

- All four auth endpoints respond with correct status codes and payloads (201/200/200/204)
- Protected routes return 401 without Bearer token; not 401 (e.g. 404) with a valid one — no protected endpoint returns 200 after HelloController is removed
- `./mvnw test` passes — all integration and unit test cases green
- Railway deploy shows `Successfully applied 2 migrations to schema "public"` in startup logs
