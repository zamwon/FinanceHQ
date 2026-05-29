# Email Notification Scheduler — Implementation Plan

## Overview

Implement the v0.1 email notification system: a Spring `@Scheduled` daily job fires at 08:00 Europe/Warsaw, computes which obligations require a notification that day (previous business day before the next due date), groups them per user, and sends a single plain-text Gmail email. A secondary hourly job retries any failed sends. A new `notification_log` table provides deduplication and retry state.

## Current State Analysis

- Full Obligation domain (entity, service, repository) is in place. `NextDueDateComputer` computes next due dates correctly for both RECURRING and FIXED_TERM periods, including month-end clamping.
- `User.getEmail()` provides the recipient address.
- Zero email or scheduling infrastructure exists: no `spring-boot-starter-mail`, no `@EnableScheduling`, no SMTP config in `application.properties`.
- Last Flyway migration: V5 (`V5__add_remaining_payments_check.sql`). New migration will be V6.
- `Obligation` entity is missing `setRemainingPayments()` — needs to be added for the scheduler to decrement the count transactionally.
- `NextDueDateComputer` is package-private in `obligation/` — `NotificationService` cannot call it directly. `ObligationService` will expose a scheduler-specific method that computes and returns targets in-package.

## Desired End State

A daily email arrives in the user's inbox at 08:00 Warsaw time on the business day before each obligation's payment date, listing all upcoming obligations in a single grouped message. Notifications that fail to send are tracked with `FAILED` status and retried every hour until successful. `remainingPayments` on FIXED_TERM obligations is decremented transactionally when a notification is marked `SENT`. The system is independently verifiable via the `notification_log` table.

### Key Discoveries:

- `NextDueDateComputer.java` is package-private — accessible from `ObligationService` but not from a new `notification` package. Solution: `ObligationService.findAllSchedulerTargets(LocalDate today)` returns pre-computed targets.
- `Obligation` has no `setRemainingPayments()` — needed for transactional decrement. Must be added.
- V5 is the last migration → notification_log will be V6.
- `User` exposes `getEmail()` — the recipient address.

## What We're NOT Doing

- No frontend changes — notification log is an internal implementation detail.
- No holiday calendar (Polish bank holidays) — business day = skip weekends only.
- No SMS/push notifications — email-only per FR-007 v0.1 scope.
- No Quartz — `@Scheduled` + `notification_log` provide sufficient retry state.
- No UI for notification history or status badges.
- No per-obligation notification preferences.
- No HTML email templates — plain text only.

## Implementation Approach

Four phases: infrastructure (schema + dependencies + config), business logic (calculator + service + obligation service extension), scheduler (two `@Scheduled` methods), tests.

**Deduplication contract:** `notification_log(obligation_id, due_date)` has a UNIQUE constraint. The daily scheduler skips any pair that already has a row. The hourly retry finds `FAILED` rows and retries; on success it updates the row to `SENT` and decrements `remainingPayments` in the same transaction.

**Send / log ordering:** `JavaMailSender.send()` is called *outside* any `@Transactional` context. Only the subsequent DB write (SENT insert + decrement, or FAILED insert) is `@Transactional`. This ordering ensures the count is only decremented when the send is confirmed.

## Critical Implementation Details

**Transaction boundary for send + log:** The `JavaMailSender.send()` call must happen outside any `@Transactional` context. A send success followed by a DB timeout must leave the obligation in a retriable state (no SENT row → daily scheduler retries next day). If send and DB write were one transaction, the inverse is also dangerous.

**Lazy-loading in retry scheduler:** `NotificationLog` holds `@ManyToOne(fetch = LAZY) Obligation`, which holds `@ManyToOne(fetch = LAZY) User`. The retry repository method must use `JOIN FETCH` for both to avoid `LazyInitializationException` when the service accesses `obligation.getUser()` outside a live Hibernate session.

**Warsaw timezone for date comparison:** Pass `LocalDate.now(ZoneId.of("Europe/Warsaw"))` explicitly to the service methods — do not rely on `LocalDate.now()` default JVM timezone, which may differ in Railway's container.

---

## Phase 1: Infrastructure

### Overview

Add all dependencies, enable scheduling, create the `notification_log` schema, wire mail config, and create the JPA entity + repo. No business logic yet.

### Changes Required:

#### 1. pom.xml — add spring-boot-starter-mail

**File**: `pom.xml`

**Intent**: Add `spring-boot-starter-mail` so `JavaMailSender` is auto-configured by Spring Boot.

**Contract**: Add inside the `<dependencies>` block alongside the other `spring-boot-starter-*` entries. No version needed — managed by the Spring Boot BOM.

#### 2. FinanceHqApplication.java — enable scheduling

**File**: `src/main/java/com/example/finance_hq/FinanceHqApplication.java`

**Intent**: Activate Spring's `@Scheduled` task executor project-wide.

**Contract**: Add `@EnableScheduling` to the class alongside `@SpringBootApplication`.

#### 3. Flyway V6 — create notification_log table

**File**: `src/main/resources/db/migration/V6__create_notification_log_table.sql`

**Intent**: Persist notification send state for deduplication and retry. The UNIQUE constraint on `(obligation_id, due_date)` is the deduplication key — one row per obligation per payment cycle.

**Contract**:
```sql
CREATE TABLE notification_log (
    id            UUID        NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    obligation_id UUID        NOT NULL REFERENCES obligations(id) ON DELETE CASCADE,
    due_date      DATE        NOT NULL,
    status        VARCHAR(10) NOT NULL CHECK (status IN ('SENT', 'FAILED')),
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    sent_at       TIMESTAMP,
    CONSTRAINT uq_notification_obligation_due UNIQUE (obligation_id, due_date)
);
CREATE INDEX idx_notification_log_status_failed ON notification_log(status) WHERE status = 'FAILED';
```

#### 4. application.properties — mail and scheduler config

**File**: `src/main/resources/application.properties`

**Intent**: Wire Gmail SMTP config with Railway env var placeholders. Per the "fallback defaults" lesson, empty fallbacks prevent startup failure locally — sends will fail gracefully, not crash the app.

**Contract**: Add below the JWT section:
```properties
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=${GMAIL_USERNAME:}
spring.mail.password=${GMAIL_APP_PASSWORD:}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
spring.task.scheduling.pool.size=2
```

#### 5. NotificationStatus enum

**File**: `src/main/java/com/example/finance_hq/notification/NotificationStatus.java`

**Intent**: Typed enum for the two notification log states.

**Contract**: `public enum NotificationStatus { SENT, FAILED }`

#### 6. NotificationLog JPA entity

**File**: `src/main/java/com/example/finance_hq/notification/NotificationLog.java`

**Intent**: JPA entity mapping `notification_log`. Holds the send result for each obligation payment cycle.

**Contract**: Fields: `UUID id` (`@GeneratedValue(strategy = UUID)`, `@Column(columnDefinition = "uuid")`), `Obligation obligation` (`@ManyToOne(fetch = LAZY)`, `@JoinColumn(name = "obligation_id")`), `LocalDate dueDate` (column `due_date`), `NotificationStatus status` (`@Enumerated(STRING)`), `LocalDateTime createdAt` (column `created_at`), `LocalDateTime sentAt` (column `sent_at`, nullable). Include: constructor for initial insert taking obligation + dueDate + status, setter for `status`, setter for `sentAt`. Follow public-before-private rule.

#### 7. NotificationLogRepository

**File**: `src/main/java/com/example/finance_hq/notification/NotificationLogRepository.java`

**Intent**: Provide the three query patterns required by the scheduler — deduplication check, FAILED row lookup, and JOIN FETCH for retry.

**Contract**: Extends `JpaRepository<NotificationLog, UUID>`. Add:
- `boolean existsByObligationIdAndDueDate(UUID obligationId, LocalDate dueDate)` — daily scheduler skip check
- `@Query("SELECT nl FROM NotificationLog nl JOIN FETCH nl.obligation o JOIN FETCH o.user WHERE nl.status = :status") List<NotificationLog> findByStatusWithObligationAndUser(@Param("status") NotificationStatus status)` — retry scheduler with eager-loaded associations

### Success Criteria:

#### Automated Verification:

- `./mvnw clean package -DskipTests` compiles without errors
- `./mvnw test` passes (no test changes in this phase — existing tests unchanged)
- V6 migration applies cleanly on app startup (logs show: `Successfully applied 1 migration to schema "public"`)

#### Manual Verification:

- `notification_log` table exists in local DB with correct columns and constraints
- App starts without errors locally: `./mvnw spring-boot:run --spring.profiles.active=local`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding.

---

## Phase 2: Business Logic

### Overview

Implement the business day calculator, extend `ObligationService` with a scheduler-facing method, and build `NotificationService` with both the daily and retry logic paths.

### Changes Required:

#### 1. Obligation.java — add setRemainingPayments

**File**: `src/main/java/com/example/finance_hq/obligation/Obligation.java`

**Intent**: Expose a setter so `NotificationService` can decrement the count transactionally when a FIXED_TERM notification is marked SENT.

**Contract**: Add `public void setRemainingPayments(Integer remainingPayments)` alongside the existing `setAmount` and `setPaymentDay` setters.

#### 2. ObligationService — add findAllSchedulerTargets

**File**: `src/main/java/com/example/finance_hq/obligation/ObligationService.java`

**Intent**: Provide a scheduler-facing query that returns all obligations with computed next due dates, excluding expired ones (null nextDueDate). Keeps `NextDueDateComputer` package-private while giving `NotificationService` the data it needs.

**Contract**: Add a public nested record `SchedulerTarget(Obligation obligation, LocalDate nextDueDate)` inside `ObligationService`. Add method:
```java
@Transactional(readOnly = true)
public List<SchedulerTarget> findAllSchedulerTargets(LocalDate today) {
    return repository.findAll().stream()
        .map(o -> new SchedulerTarget(o, NextDueDateComputer.compute(
                o.getPaymentDay(), today, o.getPeriod(), o.getEndDate())))
        .filter(t -> t.nextDueDate() != null)
        .toList();
}
```
Place below existing public methods, above the private `nextDueDate` helper. Follow the public-before-private lesson rule.

#### 3. BusinessDayCalculator

**File**: `src/main/java/com/example/finance_hq/notification/BusinessDayCalculator.java`

**Intent**: Encapsulate the "previous business day" rule — step back from a date until a weekday is reached. Handles all edge cases: Monday → Friday, Saturday → Friday, Sunday → Friday.

**Contract**: Package-private final utility class, single package-private static method `static LocalDate previousBusinessDay(LocalDate date)`. Steps `date.minusDays(1)` in a while loop while the result is `SATURDAY` or `SUNDAY`.

#### 4. NotificationService

**File**: `src/main/java/com/example/finance_hq/notification/NotificationService.java`

**Intent**: Orchestrate the full notification lifecycle — compute daily targets, send grouped emails, write log records, and handle retries. Two public entry points called by the scheduler; all DB writes are `@Transactional`.

**Contract**: `@Service`. Constructor-injected: `ObligationService`, `NotificationLogRepository`, `JavaMailSender`, `@Value("${spring.mail.username}") String fromAddress`.

**Public methods** (before private):

`runDailyNotifications(LocalDate today)`:
1. Call `obligationService.findAllSchedulerTargets(today)`.
2. Filter to targets where `BusinessDayCalculator.previousBusinessDay(nextDueDate).equals(today)` AND `!notificationLogRepository.existsByObligationIdAndDueDate(obligation.getId(), nextDueDate)`.
3. Group filtered targets by `obligation.getUser()`.
4. For each user group: call `sendGroupedEmail(user, obligations, dueDate)`. On success: call `recordSuccess(targets)`. On `MailException`: log at ERROR and call `recordFailure(targets)`.

`retryFailedNotifications()`:
1. Call `notificationLogRepository.findByStatusWithObligationAndUser(FAILED)`.
2. Group by `(obligation.getUser(), dueDate)`.
3. For each group: call `sendGroupedEmailForObligations(user, obligations, dueDate)`. On success: call `markRetrySuccess(logs)`. On `MailException`: log at ERROR and leave rows as FAILED.

**Private methods** (after public):

`void sendGroupedEmail(User user, List<ObligationService.SchedulerTarget> targets, LocalDate dueDate)` — delegates to the helper below.

`void sendGroupedEmailForObligations(User user, List<Obligation> obligations, LocalDate dueDate)` — assembles and sends a `SimpleMailMessage`. Subject: `"FinanceHQ: {N} payment(s) due {dueDate}"`. Body: `"You have {N} payment(s) due on {dueDate}:\n\n"` followed by one line per obligation `"{name} — {amount}"`, then `"\n\nThis is an automated reminder from FinanceHQ."`. Throws `MailException` on failure (not caught here — let the caller handle it).

`@Transactional void recordSuccess(List<ObligationService.SchedulerTarget> targets)` — for each target: create a `NotificationLog` with status=SENT, sentAt=now; save. For each FIXED_TERM target where `remainingPayments != null && remainingPayments > 0`: call `obligation.setRemainingPayments(remainingPayments - 1)` and save the obligation.

`@Transactional void recordFailure(List<ObligationService.SchedulerTarget> targets)` — for each target: create a `NotificationLog` with status=FAILED; save.

`@Transactional void markRetrySuccess(List<NotificationLog> logs)` — for each log: set `status = SENT`, `sentAt = now`; save. For each FIXED_TERM obligation: decrement `remainingPayments` as above.

### Success Criteria:

#### Automated Verification:

- `./mvnw clean package -DskipTests` compiles
- `./mvnw test` — all existing obligation and auth tests still pass

#### Manual Verification:

- Trace `findAllSchedulerTargets()` against local DB: add a test obligation and confirm it appears in the result with correct `nextDueDate`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding.

---

## Phase 3: Scheduler

### Overview

Wire the two `@Scheduled` methods that call into `NotificationService`.

### Changes Required:

#### 1. NotificationScheduler

**File**: `src/main/java/com/example/finance_hq/notification/NotificationScheduler.java`

**Intent**: Declare the two schedule triggers. The daily cron uses `zone = "Europe/Warsaw"` so Spring applies DST automatically — no manual UTC offset math. The retry uses `fixedDelay` so each attempt waits a full hour *after* the previous one completes, preventing overlap if retries are slow.

**Contract**: `@Component`. Constructor-injected `NotificationService`. Two methods:

```java
@Scheduled(cron = "0 0 8 * * *", zone = "Europe/Warsaw")
public void runDailyNotifications() {
    notificationService.runDailyNotifications(LocalDate.now(ZoneId.of("Europe/Warsaw")));
}

@Scheduled(fixedDelay = 3_600_000)
public void retryFailedNotifications() {
    notificationService.retryFailedNotifications();
}
```

No initial delay on the retry — finding zero FAILED rows at startup is harmless.

### Success Criteria:

#### Automated Verification:

- `./mvnw clean package -DskipTests` compiles
- `./mvnw test` passes

#### Manual Verification:

- App starts without errors; Spring logs confirm `@Scheduled` tasks are registered (look for `Scheduling: ...` in startup output)
- Add a test obligation with `paymentDay` = day after tomorrow; with SMTP credentials set, confirm email arrives at 08:00 Warsaw (or temporarily change cron to fire in minutes for a smoke test)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding.

---

## Phase 4: Tests

### Overview

Unit tests for business day logic and `NotificationService`, plus an integration test for `NotificationLogRepository` custom queries.

### Changes Required:

#### 1. BusinessDayCalculatorTest

**File**: `src/test/java/com/example/finance_hq/notification/BusinessDayCalculatorTest.java`

**Intent**: Verify the weekend-skip rule for all 7 days of the week. The double-weekend case (Sunday → Friday, not Sunday → Saturday) is the easy-to-miss edge case.

**Contract**: JUnit 5 unit test, no Spring context. Parameterized test using `@MethodSource` or `@CsvSource` covering all 7 days: Monday → Friday, Tuesday → Monday, Wednesday → Tuesday, Thursday → Wednesday, Friday → Thursday, Saturday → Friday, Sunday → Friday. Each asserts `previousBusinessDay(input).equals(expected)`.

#### 2. NotificationServiceTest

**File**: `src/test/java/com/example/finance_hq/notification/NotificationServiceTest.java`

**Intent**: Unit-test the orchestration logic without a Spring context. Covers the filter paths, grouping, deduplication skip, send failure → FAILED log, and FIXED_TERM decrement.

**Contract**: JUnit 5 + Mockito. Mock: `ObligationService`, `NotificationLogRepository`, `JavaMailSender`. Key test cases:

- Obligation with null `nextDueDate` is skipped — `send()` not called
- Obligation where `previousBusinessDay(nextDueDate) != today` is skipped — `send()` not called
- Obligation where `existsByObligationIdAndDueDate` returns `true` is skipped — `send()` not called
- Obligation where notification date == today: `send()` called once; `recordSuccess` path hit
- Two obligations for same user due same day: `send()` called once (grouped); one `SimpleMailMessage`
- `send()` throws `MailException`: `recordFailure` called; `recordSuccess` NOT called; no exception propagates
- FIXED_TERM obligation on success: `setRemainingPayments(current - 1)` called on the obligation entity and `repository.save()` is invoked

#### 3. NotificationLogRepositoryTest

**File**: `src/test/java/com/example/finance_hq/notification/NotificationLogRepositoryTest.java`

**Intent**: Integration test for the two custom repo methods against real PostgreSQL. Verifies the UNIQUE constraint and that the JOIN FETCH query returns fully-loaded associations.

**Contract**: `@SpringBootTest` + `@Transactional`. Use TC 2.x artifact names (`testcontainers-postgresql`, `testcontainers-junit-jupiter` per the lessons rule). Test cases:

- `existsByObligationIdAndDueDate` returns `false` when no row exists, `true` after insert
- Inserting two rows with the same `(obligation_id, due_date)` throws `DataIntegrityViolationException` (use `saveAndFlush()` per the `@Transactional` test lesson — the constraint is checked on flush, not on `save()`)
- `findByStatusWithObligationAndUser(FAILED)` returns only FAILED rows; accessing `.getObligation().getUser().getEmail()` outside a transaction does not throw `LazyInitializationException`

### Success Criteria:

#### Automated Verification:

- `./mvnw test -Dtest=BusinessDayCalculatorTest` passes
- `./mvnw test -Dtest=NotificationServiceTest` passes
- `./mvnw test -Dtest=NotificationLogRepositoryTest` passes
- `./mvnw test` — full suite passes with no regressions in obligation and auth tests

#### Manual Verification:

- End-to-end: add obligation due day-after-tomorrow, confirm email arrives at 08:00 Warsaw (requires Railway SMTP credentials set)
- Run scheduler a second time on the same day: confirm no duplicate email (deduplication working)
- Simulate SMTP failure (wrong password), confirm FAILED row appears, restore credentials, wait for retry, confirm SENT

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding.

---

## Testing Strategy

### Unit Tests:

- `BusinessDayCalculatorTest`: 7-case parameterized test covering all days of the week, including the double-weekend skip for Sunday
- `NotificationServiceTest`: mocked `JavaMailSender`; covers filter logic, grouping, deduplication, send failure → FAILED log, FIXED_TERM `remainingPayments` decrement

### Integration Tests:

- `NotificationLogRepositoryTest`: Testcontainers PostgreSQL (TC 2.x artifact names); verifies UNIQUE constraint (`saveAndFlush()` required), deduplication query, JOIN FETCH correctness

### Manual Testing Steps:

1. Set `GMAIL_USERNAME` and `GMAIL_APP_PASSWORD` in Railway (or local environment)
2. Log in, add a RECURRING obligation with `paymentDay` = day after tomorrow
3. Either wait for 08:00 Warsaw or temporarily change the cron to fire in a few minutes
4. Confirm email arrives in Gmail inbox with correct obligation details
5. Verify a SENT row exists in `notification_log` for the obligation
6. Run the scheduler again — confirm no second email (deduplication)
7. Set wrong `GMAIL_APP_PASSWORD`, trigger the daily scheduler — confirm FAILED row
8. Restore password — confirm hourly retry fires and updates row to SENT

## Performance Considerations

`repository.findAll()` in the daily scheduler loads all obligations in memory. For a single-user personal tool this is negligible. If the tool scales to multiple users, replace with a server-side query that computes the notification date filter in SQL.

## Migration Notes

- V6 adds `notification_log` table. No changes to existing tables or existing migration scripts.
- `Obligation` entity gains `setRemainingPayments()` — no schema change, the column already exists since V4/V5.
- Railway env vars to set before first production deployment with notifications: `GMAIL_USERNAME` (full Gmail address), `GMAIL_APP_PASSWORD` (16-character App Password from Google Account → Security → 2-Step Verification → App passwords; NOT the Gmail account password).

## References

- PRD FR-007, US-01: `context/foundation/prd.md`
- Roadmap S-04: `context/foundation/roadmap.md`
- Lessons applied: "Use fallback defaults in application.properties", "Always place private methods below public methods", "Annotate @SpringBootTest integration tests with @Transactional", "Spring Boot 4.x Testcontainers 2.x artifact names changed"

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Infrastructure

#### Automated

- [x] 1.1 `./mvnw clean package -DskipTests` compiles without errors — 41b9dbb
- [x] 1.2 `./mvnw test` passes — 41b9dbb
- [x] 1.3 V6 migration applies cleanly on app startup — 41b9dbb

#### Manual

- [x] 1.4 `notification_log` table exists in local DB with correct columns and constraints — 41b9dbb
- [x] 1.5 App starts without errors locally — 41b9dbb

### Phase 2: Business Logic

#### Automated

- [x] 2.1 `./mvnw clean package -DskipTests` compiles — cc72e9a
- [x] 2.2 `./mvnw test` — all existing tests still pass — cc72e9a

#### Manual

- [x] 2.3 `findAllSchedulerTargets()` returns correct data for a test obligation — cc72e9a

### Phase 3: Scheduler

#### Automated

- [x] 3.1 `./mvnw clean package -DskipTests` compiles — f5d8e85
- [x] 3.2 `./mvnw test` passes — f5d8e85

#### Manual

- [x] 3.3 App starts; `@Scheduled` tasks visible in startup logs — f5d8e85
- [x] 3.4 Test obligation triggers notification email end-to-end — f5d8e85

### Phase 4: Tests

#### Automated

- [x] 4.1 `./mvnw test -Dtest=BusinessDayCalculatorTest` passes
- [x] 4.2 `./mvnw test -Dtest=NotificationServiceTest` passes
- [x] 4.3 `./mvnw test -Dtest=NotificationLogRepositoryTest` passes
- [x] 4.4 `./mvnw test` — full suite passes with no regressions

#### Manual

- [ ] 4.5 End-to-end: obligation due day-after-tomorrow triggers email at 08:00 Warsaw
- [ ] 4.6 Deduplication: second scheduler run on same day sends no duplicate
- [ ] 4.7 Retry flow: FAILED row resolves to SENT after credential restore
