# Resend Email Integration Implementation Plan

## Overview

Replace the Spring Boot JavaMail/SMTP email sender with the Resend HTTP API to bypass Railway's outbound SMTP block. The current code attempts `smtp.gmail.com:587`, which Railway blocks at the TCP layer on non-Pro plans, causing every notification send to fail with `MailConnectException`.

## Current State Analysis

- `NotificationService` injects `JavaMailSender` (Spring Mail auto-configuration bean)
- `spring-boot-starter-mail` in `pom.xml` provides the bean and `SimpleMailMessage` etc.
- `application.properties` configures `spring.mail.*` pointing at `smtp.gmail.com:587`
- `NotificationServiceTest` and `NotificationServiceIntegrationTest` both mock `JavaMailSender`
- `NotificationPersistenceServiceTest` and `NotificationLogRepositoryTest` are persistence-only — no mail references, no changes needed
- Root cause confirmed: Railway blocks ports 25 / 465 / 587 outbound on Hobby plan

## Desired End State

- `NotificationService` injects `EmailSender` interface; calls `emailSender.send(to, subject, body)`
- `ResendEmailSender` implements `EmailSender`, calls Resend Java SDK, wraps `ResendException` in `MailException`
- `spring-boot-starter-mail` removed; `resend-java` added to `pom.xml`
- `application.properties` has `resend.api-key` and `resend.from-address`; no `spring.mail.*` properties remain
- All existing tests pass with `EmailSender` mocked instead of `JavaMailSender`
- Production emails deliver via Resend HTTP API from `blazej.karnecki@gmail.com`

### Key Discoveries:

- `NotificationService:33-43` injects `JavaMailSender mailSender` and `@Value("${spring.mail.username}") String fromAddress` — both go away
- The `fromAddress` is used only in `sendGroupedEmailForObligations:125` — ownership moves to `ResendEmailSender`
- `NotificationService:78,103` already catches `MailException` — catch blocks survive unchanged if we wrap `ResendException` in `MailException`
- `NotificationServiceTest` verifies `mailSender.send(any(SimpleMailMessage.class))` — becomes `emailSender.send(anyString(), anyString(), anyString())`
- `NotificationServiceIntegrationTest` uses `@MockBean JavaMailSender mailSender` — becomes `@MockBean EmailSender emailSender`
- `pom.xml` has a checkstyle plugin — keep public methods above private per `lessons.md:123-128`

## What We're NOT Doing

- Not changing the notification log DB schema
- Not changing any public REST endpoints
- Not changing scheduler cron expressions or retry intervals
- Not adding retry logic inside `ResendEmailSender` (retry is already handled by `NotificationScheduler`)
- Not migrating the from address to a custom domain (email address verification in Resend is sufficient)
- Not adding async delivery

## Implementation Approach

Introduce a thin `EmailSender` interface as the seam. Replace the `JavaMailSender` constructor parameter in `NotificationService` with `EmailSender`. Create `ResendEmailSender` as a `@Service` that wraps the Resend Java SDK. Update the two test files that mock the old interface. Remove Spring Mail and its config entirely.

## Critical Implementation Details

**`fromAddress` moves out of `NotificationService`:** Currently `NotificationService` holds `fromAddress` injected via `@Value("${spring.mail.username}")`. After the swap, `ResendEmailSender` owns the from address via `@Value("${resend.from-address}")`. Remove the `fromAddress` field and `@Value` annotation from `NotificationService`'s constructor entirely — do not pass it through the `EmailSender.send()` signature.

---

## Phase 1: Core Swap

### Overview

Replace the `JavaMailSender` wiring with `EmailSender` + `ResendEmailSender`. Update `pom.xml` and config. `NotificationServiceTest` and `NotificationServiceIntegrationTest` will break at the end of this phase — that is expected and fixed in Phase 2.

### Changes Required:

#### 1. pom.xml

**File**: `pom.xml`

**Intent**: Remove the Spring Mail SMTP dependency; add the Resend Java SDK.

**Contract**: Delete the `spring-boot-starter-mail` `<dependency>` block (lines 40-42). Add a new dependency:
```xml
<dependency>
    <groupId>com.resend</groupId>
    <artifactId>resend-java</artifactId>
    <version><!-- check Maven Central for latest stable --></version>
</dependency>
```

#### 2. EmailSender interface

**File**: `src/main/java/com/example/finance_hq/notification/EmailSender.java`

**Intent**: Define the seam between `NotificationService` and the delivery mechanism so tests can mock it without coupling to HTTP or SMTP details.

**Contract**:
```java
public interface EmailSender {
    void send(String to, String subject, String text) throws MailException;
}
```

#### 3. ResendEmailSender

**File**: `src/main/java/com/example/finance_hq/notification/ResendEmailSender.java`

**Intent**: Implement `EmailSender` using the Resend Java SDK; map `ResendException` to `MailException` so `NotificationService`'s existing catch blocks require no changes.

**Contract**: Annotate `@Service`. Constructor takes `@Value("${resend.api-key}") String apiKey` and `@Value("${resend.from-address}") String fromAddress`; construct `new Resend(apiKey)` as a field. In `send()`: build `CreateEmailOptions` with `from`, `to` (single-element list), `subject`, `text`; call `resend.emails().send(params)`; catch `ResendException` and rethrow as `new MailSendException("Resend API error: " + e.getMessage())`. Public method before the private helpers (none here), per project convention.

#### 4. application.properties

**File**: `src/main/resources/application.properties`

**Intent**: Remove all Spring Mail SMTP properties; add Resend config.

**Contract**: Delete lines 33-40 (the entire `# Mail — Gmail SMTP` block). Also delete line 5 (`management.health.mail.enabled=false` — only needed when Spring Mail is on the classpath). Add after the CORS block:
```properties
# Resend — HTTP email API (bypasses Railway SMTP block)
resend.api-key=${RESEND_API_KEY:}
resend.from-address=${RESEND_FROM_ADDRESS:blazej.karnecki@gmail.com}
```

#### 5. application-local.yml

**File**: `src/main/resources/application-local.yml`

**Intent**: Replace Gmail SMTP credentials with a Resend test API key for local dev.

**Contract**: Remove the `spring.mail.username` and `spring.mail.password` entries. Add:
```yaml
resend:
  api-key: "re_test_..."   # obtain from Resend dashboard (test keys only email to verified addresses)
  from-address: blazej.karnecki@gmail.com
```

#### 6. NotificationService

**File**: `src/main/java/com/example/finance_hq/notification/NotificationService.java`

**Intent**: Swap `JavaMailSender` constructor injection for `EmailSender`; simplify `sendGroupedEmailForObligations` to use the interface.

**Contract**: Remove imports for `JavaMailSender`, `SimpleMailMessage`, `MimeMessage`. Remove the `mailSender` and `fromAddress` fields; remove the `@Value` annotation from the constructor. Add `EmailSender emailSender` parameter and field. In `sendGroupedEmailForObligations` (line 116): replace the `SimpleMailMessage` block with a single call `emailSender.send(user.getEmail(), subject, body.toString())`. The `catch (MailException e)` blocks at lines 78 and 103 stay unchanged.

### Success Criteria:

#### Automated Verification:

- Persistence and repo tests still pass: `./mvnw test -Dtest=NotificationPersistenceServiceTest,NotificationLogRepositoryTest`
- App context loads: `./mvnw spring-boot:run` starts without `NoSuchBeanDefinitionException` or missing-property error (requires `RESEND_API_KEY` set locally or non-empty default)

#### Manual Verification:

- App starts locally with `--spring.profiles.active=local` and Resend key set in `application-local.yml`
- No `ClassNotFoundException` for `JavaMailSender` or `SimpleMailMessage` in startup logs

**Implementation Note**: `NotificationServiceTest` and `NotificationServiceIntegrationTest` will compile-fail after this phase because they still reference `JavaMailSender`. Do not run the full test suite until Phase 2 is complete. After Phase 1 manual verification passes, proceed to Phase 2.

---

## Phase 2: Test Updates

### Overview

Update the two test files that mock `JavaMailSender` to mock `EmailSender` instead. Add a unit test for `ResendEmailSender` error mapping.

### Changes Required:

#### 1. NotificationServiceTest

**File**: `src/test/java/com/example/finance_hq/notification/NotificationServiceTest.java`

**Intent**: Replace `@Mock JavaMailSender` with `@Mock EmailSender`; align verify and stub calls with the new interface signature.

**Contract**: Replace `@Mock JavaMailSender mailSender` field with `@Mock EmailSender emailSender`. Update the `NotificationService` constructor call to inject `emailSender`. Replace `verify(mailSender).send(any(SimpleMailMessage.class))` with `verify(emailSender).send(anyString(), anyString(), anyString())`. Replace `doThrow(...).when(mailSender).send(any())` stubs with the equivalent on `emailSender.send(anyString(), anyString(), anyString())`.

#### 2. NotificationServiceIntegrationTest

**File**: `src/test/java/com/example/finance_hq/notification/NotificationServiceIntegrationTest.java`

**Intent**: Replace `@MockBean JavaMailSender` with `@MockBean EmailSender`; align all interactions.

**Contract**: Replace `@MockBean JavaMailSender mailSender` with `@MockBean EmailSender emailSender`. Apply the same verify/stub pattern changes as item 1. No other structural changes needed — DB, scheduler wiring, and `@Transactional` annotations stay as-is.

#### 3. ResendEmailSenderTest (new file)

**File**: `src/test/java/com/example/finance_hq/notification/ResendEmailSenderTest.java`

**Intent**: Unit-test that a `ResendException` from the SDK is wrapped into a `MailException`.

**Contract**: Two test cases: (a) when `resend.emails().send(any())` succeeds, `emailSender.send(...)` completes without throwing; (b) when the SDK throws `ResendException`, `emailSender.send(...)` throws a `MailException`. Mock the `Resend` object or its `EmailsClient` with Mockito (inject via constructor or reflection). Annotate `@Transactional` per `lessons.md:149-155` if `@SpringBootTest` is used; plain unit test (no `@SpringBootTest`) is preferred to keep it fast.

### Success Criteria:

#### Automated Verification:

- Full test suite green: `./mvnw test`
- No `JavaMailSender` import anywhere in `src/`: `grep -r "JavaMailSender" src/` returns empty

#### Manual Verification:

- All 5 notification test files shown as passed in the Maven Surefire report

---

## Phase 3: Deploy & Verify

### Overview

Set up the Resend account, configure Railway env vars, deploy, and confirm a real email is delivered for the June 10 obligation.

### Changes Required:

#### 1. Resend account setup (manual — outside code)

**Intent**: Verify `blazej.karnecki@gmail.com` as an allowed sender so Resend will dispatch emails from that address.

**Contract**: In the Resend dashboard: navigate to **Domains → Add sender address**, enter `blazej.karnecki@gmail.com`, click the verification link sent to that inbox. Copy the production API key (`re_live_...`) from **API Keys**.

#### 2. Railway environment variables

**Intent**: Inject `RESEND_API_KEY` and remove the now-stale Gmail SMTP variables.

**Contract**: In the Railway service **Variables** panel:
- Add `RESEND_API_KEY` = production key from Resend dashboard
- Optionally add `RESEND_FROM_ADDRESS` = `blazej.karnecki@gmail.com` (or rely on the `application.properties` default)
- Delete `GMAIL_USERNAME` and `GMAIL_APP_PASSWORD`

### Success Criteria:

#### Automated Verification:

- CI deploy pipeline passes (existing `deploy.yml` health-check green after redeploy)

#### Manual Verification:

- Railway logs show no `MailConnectException` or `Retry failed` errors after the next scheduler run (08:00 Warsaw or the next hourly retry)
- Resend dashboard **Emails** tab shows the delivery record as "Delivered"
- `blazej.karnecki@gmail.com` inbox receives an email with subject `FinanceHQ: 1 payment(s) due 2026-06-10`
- `notification_log` table shows status `SENT` (not `FAILED`) for the June 10 obligation row

---

## Testing Strategy

### Unit Tests:

- `NotificationServiceTest` — mock `EmailSender`; verify `send()` called with correct `to`, `subject`, `body`; verify failure path sets FAILED status
- `ResendEmailSenderTest` — mock Resend SDK; verify `ResendException` → `MailException` wrapping

### Integration Tests:

- `NotificationServiceIntegrationTest` — `@MockBean EmailSender`; full DB + business logic (deduplication, retry, timestamp) verified against a real Postgres container

### Manual Testing Steps:

1. After deploy: check Railway logs — confirm no `MailConnectException`
2. Check Resend dashboard — confirm delivery record present and status "Delivered"
3. Check inbox — email received with correct subject and obligation details
4. Check DB `notification_log` — row for June 10 obligation has `status = 'SENT'`

## Migration Notes

The `GMAIL_USERNAME` and `GMAIL_APP_PASSWORD` Railway variables become dead config once this ships. Delete them in Phase 3 to avoid confusion. The `application-local.yml` Gmail password (`khaa psek aauy akey`) is no longer needed either — it is replaced by the Resend test key in Phase 1.

## References

- Related archived plan: `context/archive/2026-05-29-email-notification-scheduler/plan.md`
- Resend Java SDK: https://github.com/resend/resend-java
- `NotificationService.java:116-131` — current `sendGroupedEmailForObligations` to replace
- `application.properties:33-40` — Spring Mail block to delete
- Debugging session that identified root cause: Railway `MailConnectException` + outbound SMTP blocked

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Core Swap

#### Automated

- [x] 1.1 Persistence and repo tests still pass: `./mvnw test -Dtest=NotificationPersistenceServiceTest,NotificationLogRepositoryTest`
- [x] 1.2 App context loads without errors: `./mvnw spring-boot:run`

#### Manual

- [x] 1.3 App starts locally with `--spring.profiles.active=local` and Resend key set — no `ClassNotFoundException` or `NoSuchBeanDefinitionException`

### Phase 2: Test Updates

#### Automated

- [ ] 2.1 Full test suite green: `./mvnw test`
- [ ] 2.2 No `JavaMailSender` import in `src/`: `grep -r "JavaMailSender" src/` returns empty

#### Manual

- [ ] 2.3 All 5 notification test files shown as passed in Maven Surefire report

### Phase 3: Deploy & Verify

#### Automated

- [ ] 3.1 CI deploy pipeline passes after push to master

#### Manual

- [ ] 3.2 No `MailConnectException` or `Retry failed` in Railway logs after redeploy
- [ ] 3.3 Resend dashboard shows delivery record as "Delivered"
- [ ] 3.4 `blazej.karnecki@gmail.com` inbox receives email with subject `FinanceHQ: 1 payment(s) due 2026-06-10`
- [ ] 3.5 `notification_log` row for June 10 obligation has `status = 'SENT'`
