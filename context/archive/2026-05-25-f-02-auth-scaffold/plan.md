# F-02 Auth Scaffold Implementation Plan

## Overview

Wire Spring Security 7.x over the existing persistence layer: four REST endpoints (`POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`) backed by JJWT-signed access tokens (1h TTL) and PostgreSQL-stored refresh tokens (30d TTL, rotated on every use). All routes except those four and `GET /actuator/health` require a valid `Authorization: Bearer <token>` header.

## Current State Analysis

- `users` table (V1 migration): `id`, `email` (UNIQUE), `password_hash`, `created_at` — ready to map to a JPA entity
- Zero Spring Security code; `spring-boot-starter-security` not in `pom.xml`
- One stub controller (`HelloController.java`) to remove
- Single test class: `@SpringBootTest` + Testcontainers `@ServiceConnection` pattern established and proven
- `application.properties`: datasource and Flyway wired; no security or JWT properties yet

## Desired End State

Four auth endpoints respond correctly: registration persists a BCrypt-hashed user; login returns a signed JWT access token and a DB-stored refresh token; refresh rotates both tokens atomically; logout deletes the refresh token from DB. All other routes return 401 without a valid Bearer token. Spring context loads, Flyway V2 migration applies (`refresh_tokens` table exists), and the full test suite passes.

### Key Discoveries

- `users.password_hash` column already exists — BCrypt output maps directly (`src/main/resources/db/migration/V1__create_users_table.sql:4`)
- Testcontainers pattern established: `PostgreSQLContainer<>("postgres:16-alpine")` + `@ServiceConnection` (`src/test/java/com/example/finance_hq/FinanceHqApplicationTests.java:17`)
- No HTTP endpoint tests yet — `contextLoads()` only verifies Spring context startup
- JJWT 0.12.x has a breaking API change from 0.11.x — see Critical Implementation Details

## What We're NOT Doing

- OAuth, SSO, or passwordless authentication
- Email verification (OTP/confirm link) — PRD Non-Goals
- Access token revocation (1h TTL is the mitigation)
- Refresh token blacklist (rotation handles replay attacks)
- RFC 7807 Problem Details error format — MVP uses simple JSON `{ "error": "..." }`; see `context/foundation/lessons.md` for future migration path
- Password reset / forgot-password flow
- Angular frontend wiring — F-03 scope

## Implementation Approach

Standard Spring Security 7.x stateless REST setup: CSRF disabled, session policy STATELESS. `JwtAuthenticationFilter` (extends `OncePerRequestFilter`) validates the Bearer token per request and sets the `SecurityContext`. `AuthService` (also implements `UserDetailsService`) handles all four auth flows, delegating to `JwtService` (token generation/validation) and `RefreshTokenService` (DB lifecycle and rotation).

Package layout:
- `auth/` — `AuthController`, `AuthService`, `JwtService`, `RefreshTokenService`, `dto/`, `exception/`
- `security/` — `SecurityConfig`, `JwtAuthenticationFilter`
- `user/` — `User`, `UserRepository`, `RefreshToken`, `RefreshTokenRepository`

## Critical Implementation Details

**JwtAuthenticationFilter servlet double-registration:** A `@Component`-annotated filter is auto-registered in the Tomcat servlet filter chain by Spring Boot AND added to the Spring Security chain when `addFilterBefore` is called — causing double invocation. Prevent this by registering a `FilterRegistrationBean<JwtAuthenticationFilter>` with `setEnabled(false)` inside `SecurityConfig`.

**JJWT 0.12.x API:** `Jwts.parserBuilder()` was removed. Use `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)`. Signing key from Base64 secret: `Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))` (minimum 256-bit for HMAC-SHA-256).

**Spring Security 7.x lambda DSL:** The method-chaining style (`.authorizeRequests().antMatchers(...)`) was removed. Use only the lambda DSL: `.authorizeHttpRequests(auth -> auth.requestMatchers(...).permitAll()...)`.

---

## Phase 1: Foundation — Dependencies, Migration, Properties

### Overview

Add all new dependencies to `pom.xml`, create the Flyway V2 migration for `refresh_tokens`, add JWT and CORS properties to `application.properties`, and remove `HelloController.java`.

### Changes Required

#### 1. pom.xml — new dependencies

**File:** `pom.xml`

**Intent:** Add Spring Security, Bean Validation, JJWT (API + runtime impl + Jackson serializer), and Spring Security Test to the project.

**Contract:** Add six dependency declarations. JJWT artifacts require an explicit `<version>0.12.6</version>`; all others are version-managed by the Boot BOM. `jjwt-impl` and `jjwt-jackson` use `<scope>runtime</scope>`; `spring-security-test` uses `<scope>test</scope>`.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

#### 2. V2 Flyway migration — refresh_tokens table

**File:** `src/main/resources/db/migration/V2__create_refresh_tokens_table.sql`

**Intent:** Create the `refresh_tokens` table for server-side refresh token storage, enabling true revocation on logout and atomic rotation.

**Contract:**
```sql
CREATE TABLE refresh_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    token      VARCHAR(512) NOT NULL UNIQUE,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP    NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

#### 3. application.properties — JWT and CORS properties

**File:** `src/main/resources/application.properties`

**Intent:** Inject JWT signing key and expiry windows via Railway env vars; set CORS allowed origins with a localhost:4200 default for local development.

**Contract:**
```properties
# JWT
jwt.secret=${JWT_SECRET}
jwt.access-token.expiry=3600
jwt.refresh-token.expiry=2592000

# CORS
cors.allowed-origins=${CORS_ALLOWED_ORIGINS:http://localhost:4200}
```

`JWT_SECRET` must be a Base64-encoded random value of at least 32 bytes (256 bits). Generate with: `openssl rand -base64 32`.

#### 4. Delete HelloController.java

**File:** `src/main/java/com/example/finance_hq/HelloController.java`

**Intent:** Remove the stub endpoint that was the deploy smoke test. Auth endpoints replace this role.

**Contract:** Delete the file. No other source file references it.

### Success Criteria

#### Automated Verification

- `./mvnw clean package -DskipTests` exits 0 — all deps resolve; V2 migration file is syntactically valid
- `./mvnw test` passes — `contextLoads()` passes with Spring Security on classpath and V2 migration applied by Testcontainers

#### Manual Verification

- `JWT_SECRET` (Base64, 32+ bytes) and `CORS_ALLOWED_ORIGINS` added to Railway environment variables before next deploy

**Implementation Note:** Pause after automated verification to confirm Railway env vars are set, then proceed to Phase 2.

---

## Phase 2: Core Domain — Entities and Repositories

### Overview

Map both DB tables to JPA entities and wire Spring Data JPA repositories. `User` implements `UserDetails` to serve as the Spring Security principal directly.

### Changes Required

#### 1. User entity

**File:** `src/main/java/com/example/finance_hq/user/User.java`

**Intent:** Map the `users` table; implement `UserDetails` so `AuthService.loadUserByUsername` can return it directly without a wrapper.

**Contract:** `@Entity @Table(name = "users")`. Fields: `id` (`Long`, `@Id @GeneratedValue(IDENTITY)`), `email` (`VARCHAR(255)`, unique, not null), `passwordHash` (`@Column(name = "password_hash")`), `createdAt` (`LocalDateTime`, `@Column(name = "created_at")`). `UserDetails` implementation: `getUsername()` → `email`; `getPassword()` → `passwordHash`; `getAuthorities()` → `List.of()` (no roles in MVP); all boolean methods → `true`.

#### 2. UserRepository

**File:** `src/main/java/com/example/finance_hq/user/UserRepository.java`

**Intent:** Look up users by email for the login flow and duplicate-email check at registration.

**Contract:** `extends JpaRepository<User, Long>`. Declare `Optional<User> findByEmail(String email)` and `boolean existsByEmail(String email)`.

#### 3. RefreshToken entity

**File:** `src/main/java/com/example/finance_hq/user/RefreshToken.java`

**Intent:** Map the `refresh_tokens` table for create, rotate, and revoke operations.

**Contract:** `@Entity @Table(name = "refresh_tokens")`. Fields: `id` (`Long`), `token` (`VARCHAR(512)`, unique, not null), `user` (`@ManyToOne(fetch = LAZY) @JoinColumn(name = "user_id")`), `expiresAt` (`LocalDateTime`), `createdAt` (`LocalDateTime`, default via entity constructor — `LocalDateTime.now()`).

#### 4. RefreshTokenRepository

**File:** `src/main/java/com/example/finance_hq/user/RefreshTokenRepository.java`

**Intent:** Spring Data repository for refresh token lifecycle: find by token string, delete by token string, delete all tokens for a user.

**Contract:** `extends JpaRepository<RefreshToken, Long>`. Declare `Optional<RefreshToken> findByToken(String token)`, `@Modifying @Transactional void deleteByToken(String token)`, `@Modifying @Transactional void deleteByUser(User user)`.

### Success Criteria

#### Automated Verification

- `./mvnw test` passes — context loads with new entities; Testcontainers applies V1 + V2 migrations; Hibernate validates column name mappings without errors

#### Manual Verification

- Test output shows no `HibernateException` or column-not-found warnings

---

## Phase 3: Token Services

### Overview

Implement `JwtService` (JJWT-backed access token generation and validation) and `RefreshTokenService` (DB-backed refresh token lifecycle: create, rotate, revoke).

### Changes Required

#### 1. JwtService

**File:** `src/main/java/com/example/finance_hq/auth/JwtService.java`

**Intent:** Encapsulate all JJWT operations. Reads signing key and expiry from injected properties.

**Contract:** `@Service`. Inject `@Value("${jwt.secret}") String secret` and `@Value("${jwt.access-token.expiry}") long accessTokenExpiry`. Public methods:
- `String generateAccessToken(String email)` — subject = email; expiry = now + accessTokenExpiry seconds; signed with HMAC-SHA-256
- `String extractEmail(String token)` — parse and return subject claim
- `boolean isTokenValid(String token)` — validate signature and expiry; return `false` on any `JwtException`

Key derivation (use this exact form — breaking change from 0.11.x):
```java
Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))
```

#### 2. Test properties file — prevent contextLoads() breakage

**File:** `src/test/resources/application-test.properties`

**Intent:** Once `JwtService` is added to the Spring context, `contextLoads()` in `FinanceHqApplicationTests` fails because `${JWT_SECRET}` cannot be resolved in the test environment. A profile-based test properties file supplies the required values without touching `application.properties`.

**Contract:**
```properties
jwt.secret=dGVzdHNlY3JldHRlc3RzZWNyZXR0ZXN0c2VjcmV0dGVzdA==
jwt.access-token.expiry=3600
jwt.refresh-token.expiry=2592000
```
The Base64 value above is a valid 32-byte test secret. Also add `@ActiveProfiles("test")` to `FinanceHqApplicationTests`. `AuthControllerIntegrationTest` (Phase 6) should use the same `@ActiveProfiles("test")` instead of `@TestPropertySource`.

#### 3. RefreshTokenService

**File:** `src/main/java/com/example/finance_hq/auth/RefreshTokenService.java`

**Intent:** Manage refresh token persistence. `create` generates a UUID token; `rotate` atomically deletes and recreates; `revoke` deletes without error if absent.

**Contract:** `@Service`. Inject `RefreshTokenRepository` and `@Value("${jwt.refresh-token.expiry}") long refreshTokenExpiry`. Methods:
- `RefreshToken create(User user)` — token = `UUID.randomUUID().toString()`; expiresAt = `now + refreshTokenExpiry` seconds; persist and return
- `@Transactional RefreshToken rotate(String oldToken)` — `findByToken(oldToken)` → throw `InvalidRefreshTokenException` if absent or if `expiresAt.isBefore(now)`; `deleteByToken(oldToken)`; return `create(sameUser)`
- `void revoke(String token)` — `deleteByToken(token)` (no-op if absent — this is intentional)

### Success Criteria

#### Automated Verification

- Unit tests pass: `JwtServiceTest` — generate token; extract email; validate fresh token; reject expired token (0-second expiry); reject tampered signature
- Unit tests pass: `RefreshTokenServiceTest` — create persists row; rotate returns new token and deletes old; rotate on expired token throws `InvalidRefreshTokenException`; revoke deletes row

#### Manual Verification

- None required for this phase

---

## Phase 4: Auth Endpoints and Error Handling

### Overview

Define request/response DTOs with Bean Validation constraints, implement `AuthService` (business logic + `UserDetailsService`), expose `AuthController` (four thin endpoints), and add `GlobalExceptionHandler` for consistent error responses.

### Changes Required

#### 1. DTOs

**Files:**
- `src/main/java/com/example/finance_hq/auth/dto/RegisterRequest.java` — `@NotBlank @Email String email`; `@NotBlank @Size(min = 8) @Pattern(regexp = "(?=.*[A-Z])(?=.*[0-9])(?=.*[@#$%^&+=!?]).*", message = "must contain uppercase, digit, and special character") String password`
- `src/main/java/com/example/finance_hq/auth/dto/LoginRequest.java` — `@NotBlank String email`; `@NotBlank String password`
- `src/main/java/com/example/finance_hq/auth/dto/RefreshRequest.java` — `@NotBlank String refreshToken`
- `src/main/java/com/example/finance_hq/auth/dto/LogoutRequest.java` — `@NotBlank String refreshToken`
- `src/main/java/com/example/finance_hq/auth/dto/TokenResponse.java` — `String accessToken`; `String refreshToken`; `String tokenType = "Bearer"`; `long expiresIn` (seconds, value from `jwt.access-token.expiry`)

**Intent:** Carry validated request data into the service layer; carry token data back to the client.

**Contract:** Java records or simple POJOs with a constructor. No logic in DTOs.

#### 2. Custom exceptions

**Files:**
- `src/main/java/com/example/finance_hq/auth/exception/EmailAlreadyExistsException.java`
- `src/main/java/com/example/finance_hq/auth/exception/InvalidRefreshTokenException.java`

**Intent:** Typed exceptions for `GlobalExceptionHandler` to map to specific HTTP statuses.

**Contract:** Both extend `RuntimeException` with a `String message` constructor. No extra fields needed.

#### 3. AuthService

**File:** `src/main/java/com/example/finance_hq/auth/AuthService.java`

**Intent:** Business logic for all four auth flows. Also implements `UserDetailsService` so the JWT filter can load user details by email.

**Contract:** `@Service implements UserDetailsService`. Inject `UserRepository`, `PasswordEncoder`, `JwtService`, `RefreshTokenService`.

Methods:
- `void register(RegisterRequest req)` — `existsByEmail` → throw `EmailAlreadyExistsException` if true; `passwordEncoder.encode(req.password())`; save new `User`; return (no token — user must login separately)
- `TokenResponse login(LoginRequest req)` — `findByEmail` → throw `BadCredentialsException` if absent; `passwordEncoder.matches` → throw `BadCredentialsException` if mismatch (same exception for both cases — prevents email enumeration); generate access token; create refresh token; return `TokenResponse`
- `TokenResponse refresh(String refreshToken)` — delegate to `RefreshTokenService.rotate`; extract user from result; generate new access token; return `TokenResponse`
- `void logout(String refreshToken)` — delegate to `RefreshTokenService.revoke`
- `UserDetails loadUserByUsername(String email)` — `findByEmail` → throw `UsernameNotFoundException` if absent

#### 4. AuthController

**File:** `src/main/java/com/example/finance_hq/auth/AuthController.java`

**Intent:** HTTP boundary for auth. Thin: validate input with `@Valid`, delegate to `AuthService`, return status codes.

**Contract:** `@RestController @RequestMapping("/auth")`. Endpoints:
- `POST /auth/register` — `@Valid @RequestBody RegisterRequest` → `authService.register(req)` → `ResponseEntity.status(201).build()`
- `POST /auth/login` — `@Valid @RequestBody LoginRequest` → `ResponseEntity.ok(authService.login(req))`
- `POST /auth/refresh` — `@Valid @RequestBody RefreshRequest` → `ResponseEntity.ok(authService.refresh(req.refreshToken()))`
- `POST /auth/logout` — `@Valid @RequestBody LogoutRequest` → `authService.logout(req.refreshToken())` → `ResponseEntity.noContent().build()`

#### 5. GlobalExceptionHandler

**File:** `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java`

**Intent:** Map typed exceptions to HTTP responses with `{ "error": "..." }` JSON. Prevents stack traces from reaching clients.

**Contract:** `@RestControllerAdvice`. Handlers return `Map.of("error", message)`:
- `EmailAlreadyExistsException` → 409 `"Email already registered"`
- `InvalidRefreshTokenException` → 401 `"Invalid or expired refresh token"`
- `UsernameNotFoundException` and `BadCredentialsException` → 401 `"Invalid credentials"` (same message — no email enumeration)
- `MethodArgumentNotValidException` → 400 `{ "error": "Validation failed", "details": [list of field + defaultMessage] }`

### Success Criteria

#### Automated Verification

- `./mvnw clean package -DskipTests` exits 0

#### Manual Verification

- None required; endpoints are tested in Phase 6

---

## Phase 5: Security Filter Chain and CORS

### Overview

Configure the Spring Security filter chain (permitAll routes, STATELESS sessions, CSRF off), implement `JwtAuthenticationFilter`, define `BCryptPasswordEncoder` and CORS beans. The application is fully secured after this phase.

### Changes Required

#### 1. SecurityConfig

**File:** `src/main/java/com/example/finance_hq/security/SecurityConfig.java`

**Intent:** Define the `SecurityFilterChain`: disable CSRF, enforce stateless sessions, declare permitted routes, add the JWT filter, configure CORS, define `PasswordEncoder` and `FilterRegistrationBean`.

**Contract:** `@Configuration @EnableWebSecurity`. Beans:

`SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)`:
```java
http
    .csrf(AbstractHttpConfigurer::disable)
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
        .anyRequest().authenticated()
    )
    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
    .cors(cors -> cors.configurationSource(corsConfigurationSource()));
return http.build();
```

`PasswordEncoder passwordEncoder()` — returns `new BCryptPasswordEncoder()`.

`CorsConfigurationSource corsConfigurationSource()` — inject `@Value("${cors.allowed-origins}") String allowedOrigins`; split on comma; allowed methods GET/POST/PUT/DELETE/OPTIONS; allowed headers `*`; `allowCredentials(true)`; register on `/**`.

`FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter)` — `setEnabled(false)` to prevent Tomcat servlet chain double-registration (see Critical Implementation Details).

#### 2. JwtAuthenticationFilter

**File:** `src/main/java/com/example/finance_hq/security/JwtAuthenticationFilter.java`

**Intent:** Read the Bearer token from each request, validate it, load the user, and set the `SecurityContextHolder` authentication if the token is valid.

**Contract:** `@Component extends OncePerRequestFilter`. Inject `JwtService` and `UserDetailsService`.

`doFilterInternal` logic:
1. Read `Authorization` header; if absent or not starting with `"Bearer "`, continue filter chain and return
2. Extract token (substring after `"Bearer "`)
3. `jwtService.isTokenValid(token)` → if false, continue filter chain and return
4. `jwtService.extractEmail(token)` → load `UserDetails` via `userDetailsService.loadUserByUsername(email)`
5. Construct `UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())`; set `WebAuthenticationDetailsSource().buildDetails(request)` on it; set in `SecurityContextHolder.getContext().setAuthentication(...)`
6. Continue filter chain

### Success Criteria

#### Automated Verification

- `./mvnw test` passes — context loads with full security config; `contextLoads()` passes

#### Manual Verification

- `./mvnw spring-boot:run` starts without error
- `curl -X POST http://localhost:8080/auth/register -H "Content-Type: application/json" -d '{"email":"test@example.com","password":"Test1234!"}' -v` returns 201
- `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}` without auth header
- `curl http://localhost:8080/some-other-path` returns 401

**Implementation Note:** This is the highest-risk phase. Confirm all three manual checks before proceeding to Phase 6.

---

## Phase 6: Tests

### Overview

Write unit tests for `JwtService` and integration tests for all four endpoints covering happy paths and key error cases. Follows the established Testcontainers `@ServiceConnection` pattern.

### Changes Required

#### 1. JwtServiceTest

**File:** `src/test/java/com/example/finance_hq/auth/JwtServiceTest.java`

**Intent:** Verify token generation, claim extraction, and validation logic in isolation — no Spring context needed.

**Contract:** Plain JUnit 5 (no `@SpringBootTest`). Instantiate `JwtService` directly with a test secret (Base64 of 32 random bytes) and a short expiry. Test cases:
- `generateToken_subjectIsEmail` — extracted subject equals input email
- `isTokenValid_trueForFreshToken`
- `isTokenValid_falseForExpiredToken` — use -1 second expiry (token expired at creation time); assert `isTokenValid` returns false
- `isTokenValid_falseForTamperedToken` — modify one character in the compact token string

#### 2. AuthControllerIntegrationTest

**File:** `src/test/java/com/example/finance_hq/auth/AuthControllerIntegrationTest.java`

**Intent:** End-to-end integration tests using the real filter chain, real BCrypt, real PostgreSQL (Testcontainers), and real JWT.

**Contract:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Testcontainers` + `@ServiceConnection` + `@ActiveProfiles("test")`. Use `TestRestTemplate`. JWT properties are supplied by `src/test/resources/application-test.properties` (added in Phase 3). Test cases:
- `register_201_validCredentials`
- `register_409_duplicateEmail`
- `register_400_weakPassword` — fails `@Pattern` constraint
- `register_400_invalidEmail`
- `login_200_correctCredentials` — response contains non-null `accessToken` and `refreshToken`
- `login_401_wrongPassword`
- `login_401_unknownEmail` — same 401 status as wrong password (no enumeration)
- `refresh_200_validRefreshToken` — rotated tokens returned
- `refresh_401_invalidRefreshToken`
- `logout_204_validRefreshToken`
- `protectedEndpoint_401_noToken` — any route not in permitAll list
- `protectedEndpoint_notUnauthorized_validBearerToken` — attach login-issued access token to a request for an unknown path; assert response status is NOT 401 (security filter passed through; route returns 404 from MVC)

### Success Criteria

#### Automated Verification

- `./mvnw test` passes — all 12 integration test cases + 4 JwtService unit test cases pass
- `./mvnw dependency:tree | grep javax` returns empty (no legacy javax.* dependencies)

#### Manual Verification

- Full auth flow via cURL/Postman: register → login → hit protected route with Bearer token → refresh → logout → confirm refresh token returns 401 after logout
- Railway deploy shows `Successfully applied 2 migrations to schema "public"` in startup logs
- Supabase Table Editor shows `refresh_tokens` table with `id`, `token`, `user_id`, `expires_at`, `created_at` columns

---

## Testing Strategy

### Unit Tests

- `JwtServiceTest`: token generation, claim extraction, expiry detection, tamper detection (plain JUnit 5)

### Integration Tests

- `AuthControllerIntegrationTest`: all four endpoints, 12 test cases, real DB via Testcontainers + `@ServiceConnection`

### Manual Testing Steps

1. Register with valid email + strong password → 201
2. Register same email again → 409
3. Login with correct credentials → 200 with `accessToken` and `refreshToken`
4. Hit any non-auth route without Bearer token → 401
5. Hit any non-auth route with valid Bearer token → 200 (or 404, not 401)
6. `POST /auth/refresh` with refresh token → new `accessToken` and `refreshToken`
7. `POST /auth/logout` with refresh token → 204
8. `POST /auth/refresh` with the revoked refresh token → 401

## Performance Considerations

`BCryptPasswordEncoder` uses strength 10 (default) — approximately 100–300ms per hash/verify on commodity hardware. Acceptable for a personal tool with no concurrent load. HikariCP pool size stays at 5 (Supabase Session Pooler limit set in F-01).

## Migration Notes

V2 migration is purely additive. No existing data is affected. The `baseline-on-migrate=true` and `baseline-version=0` settings from F-01 remain correct and unchanged; V2 applies on top of V1 cleanly.

## References

- F-01 plan (archived): `context/archive/2026-05-25-f-01-data-persistence-scaffold/plan.md`
- Lessons (pitfalls): `context/foundation/lessons.md`
- Infrastructure (Railway/Supabase wiring): `context/foundation/infrastructure.md`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Foundation — Dependencies, Migration, Properties

#### Automated

- [x] 1.1 `./mvnw clean package -DskipTests` exits 0 — 21250f6
- [x] 1.2 `./mvnw test` passes with Spring Security on classpath — 21250f6

#### Manual

- [x] 1.3 `JWT_SECRET` and `CORS_ALLOWED_ORIGINS` added to Railway environment variables — 21250f6

### Phase 2: Core Domain — Entities and Repositories

#### Automated

- [x] 2.1 `./mvnw test` passes — V1 + V2 migrations applied; Hibernate column mappings validated

#### Manual

- [x] 2.2 No `HibernateException` or column-not-found warnings in test output

### Phase 3: Token Services

#### Automated

- [x] 3.1 `JwtServiceTest` unit tests pass (generate, extract email, validate fresh, reject expired, reject tampered) — 83cb023
- [x] 3.2 `RefreshTokenServiceTest` unit tests pass (create, rotate, rotate-on-expired throws, revoke) — 83cb023

### Phase 4: Auth Endpoints and Error Handling

#### Automated

- [x] 4.1 `./mvnw clean package -DskipTests` exits 0 — 5d41d9c

### Phase 5: Security Filter Chain and CORS

#### Automated

- [x] 5.1 `./mvnw test` passes — context loads with full security config — 7523c7c

#### Manual

- [x] 5.2 `POST /auth/register` returns 201 via local `spring-boot:run` — 7523c7c
- [x] 5.3 `curl /actuator/health` returns `{"status":"UP"}` without auth header — 7523c7c
- [x] 5.4 Request to an unrecognised route returns 401 — 7523c7c

### Phase 6: Tests

#### Automated

- [x] 6.1 `./mvnw test` passes — all integration and unit test cases pass — a99e409
- [x] 6.2 `./mvnw dependency:tree | grep javax` returns empty — a99e409

#### Manual

- [x] 6.3 Full auth flow verified via cURL/Postman (register → login → protected route → refresh → logout → confirm revocation)
- [x] 6.4 Railway deploy shows `Successfully applied 2 migrations to schema "public"` in startup logs
- [x] 6.5 Supabase Table Editor shows `refresh_tokens` table with expected columns
