---
date: 2026-06-01T11:03:15Z
researcher: Blazej Karnecki
git_commit: 971c41f7cf3b4856f72a364dfc4875a38ac81ea3
branch: master
repository: FinanceHQ
topic: "Phase 1 — Notification Pipeline Reliability: Scheduler, Date Logic, Email Delivery, and Deduplication"
tags: [research, notification-pipeline, scheduler, date-computation, email-service, deduplication, transaction-boundaries]
status: complete
last_updated: 2026-06-01
last_updated_by: Blazej Karnecki
---

# Research: Phase 1 — Notification Pipeline Reliability

**Date**: 2026-06-01T11:03:15Z  
**Researcher**: Blazej Karnecki  
**Git Commit**: 971c41f7cf3b4856f72a364dfc4875a38ac81ea3  
**Branch**: master  
**Repository**: FinanceHQ  

## Research Question

**Phase 1 Scope:** Prove notification pipeline reliability for three risks from `context/foundation/test-plan.md`:
- **Risk #1**: Notification email fails to arrive before payment deadline (scheduler runs but email never reaches user)
- **Risk #2**: Scheduler computes wrong notification date (timezone/business-day edge case)
- **Risk #6**: Notification retry produces duplicate emails (email sends successfully but DB write fails)

**What we're investigating:**
1. Where the scheduler lives and how it determines eligible obligations
2. How the system computes notification dates and handles timezones/business days
3. How the email service sends notifications and what failure modes exist
4. How deduplication is enforced and where the transaction boundaries are

## Summary

The notification pipeline **exists and is fully implemented**, with a scheduler that runs daily at 8 AM Europe/Warsaw time and hourly retry mechanism for failed sends. The implementation splits email sending (outside any Spring transaction) from log recording (in a separate transaction), which creates a critical vulnerability: if email sends successfully but the DB log write fails, the next scheduler run will resend the same email (duplicate).

**Key Findings:**
- ✓ Scheduler exists with timezone config (hardcoded to Europe/Warsaw)
- ✓ Business-day calculator implemented (skips weekends, no public holiday support)
- ✓ Date computation includes month-end clamping (31st in Feb clamps to 28th)
- ✓ Email service fully wired with JavaMailSender and Gmail SMTP
- ✓ UNIQUE(obligation_id, due_date) constraint exists to prevent log duplicates
- ⚠️ **Critical gap**: Send and log write are separate transactions → duplicate emails possible if log fails
- ⚠️ **Medium risk**: Timezone hardcoded to Europe/Warsaw → multi-timezone support missing

**Test layer implications:**
- Unit tests needed: business-day calculator, date computation (month-end edge cases)
- Integration tests needed: end-to-end scheduler → email → log write under normal and failure conditions
- Specific failure test: email succeeds, DB write fails, verify no duplicate on retry (requires transaction injection)

---

## Detailed Findings

### Component 1: Scheduler Trigger Mechanism

**Files:**
- `src/main/java/com/example/finance_hq/notification/NotificationScheduler.java` — scheduler class with @Scheduled annotations
- `src/main/java/com/example/finance_hq/notification/NotificationService.java` — business logic for finding and notifying obligations

**Scheduler Configuration:**

```java
@Scheduled(cron = "0 0 8 * * *", zone = "Europe/Warsaw")
public void runDailyNotifications() {
    notificationService.runDailyNotifications(LocalDate.now(ZoneId.of("Europe/Warsaw")));
}

@Scheduled(fixedDelay = 3_600_000)  // 1 hour in milliseconds
public void retryFailedNotifications() {
    notificationService.retryFailedNotifications();
}
```

**What runs:**
- **Daily trigger** at 8 AM Europe/Warsaw time — finds all obligations due "today" and sends notifications
- **Hourly retry** — finds all FAILED notification log entries and attempts resend

**Obligation Eligibility Query:**

The scheduler calls `obligationService.findAllSchedulerTargets(LocalDate today)` which:
1. Loads all obligations with their users (eager fetch)
2. Computes `nextDueDate` for each obligation using `NextDueDateComputer`
3. Filters to only those with non-null next due dates
4. Returns as `SchedulerTarget` objects (obligation + computed nextDueDate)

**Key observation:** The query is not filtered by date; all obligations are loaded and computed in memory. This is OK for MVP scale but means N obligations are processed even if only a few are eligible on any given day.

---

### Component 2: Business-Day Calculator and Date Logic

#### Timezone Configuration

**Hardcoded timezone: Europe/Warsaw**

Appears in three locations:
1. `NotificationScheduler.java:18` — cron job `zone = "Europe/Warsaw"`
2. `NotificationScheduler.java:20` — `LocalDate.now(ZoneId.of("Europe/Warsaw"))`
3. `NotificationPersistenceService.java:28, 49` — `LocalDateTime.now(ZoneId.of("Europe/Warsaw"))`

**Risk:** Multi-timezone support is not implemented. If obligations are due in different timezones (e.g., user in US but obligation in EUR), the 8 AM Warsaw notification window will not align with the user's local time.

#### Business-Day Calculator

**File:** `src/main/java/com/example/finance_hq/notification/BusinessDayCalculator.java`

```java
static LocalDate previousBusinessDay(LocalDate date) {
    LocalDate result = date.minusDays(1);
    while (result.getDayOfWeek() == DayOfWeek.SATURDAY || result.getDayOfWeek() == DayOfWeek.SUNDAY) {
        result = result.minusDays(1);
    }
    return result;
}
```

**Logic:** Go back 1 day, keep going back while landing on Saturday or Sunday. Stops at the first weekday.

**What it handles:**
- ✓ Monday due → Friday notify (go back 3 days, skip Sat/Sun)
- ✓ Friday due → Thursday notify (go back 1 day)
- ✗ Public holidays — NOT handled (no holiday calendar)

**Test coverage exists:** `src/test/java/com/example/finance_hq/notification/BusinessDayCalculatorTest.java` validates weekday-to-weekday transitions.

#### Next Due Date Computation

**File:** `src/main/java/com/example/finance_hq/obligation/NextDueDateComputer.java`

```java
public static LocalDate compute(int paymentDay, LocalDate today, ObligationPeriod period, LocalDate endDate) {
    // Guard: FIXED_TERM already past end date → no more notifications
    if (period == ObligationPeriod.FIXED_TERM && endDate != null && endDate.isBefore(today)) {
        return null;
    }

    // Clamp payment day to this month (e.g., 31st → 28th for Feb)
    LocalDate candidate = clampToMonth(paymentDay, YearMonth.from(today));
    
    // If already passed this month, move to next month
    if (candidate.isBefore(today)) {
        candidate = clampToMonth(paymentDay, YearMonth.from(today).plusMonths(1));
    }

    // Guard: FIXED_TERM candidate after end date → use end date instead
    if (period == ObligationPeriod.FIXED_TERM && endDate != null && candidate.isAfter(endDate)) {
        return endDate;
    }

    return candidate;
}

private static LocalDate clampToMonth(int paymentDay, YearMonth month) {
    int lastDay = month.lengthOfMonth();
    return month.atDay(Math.min(paymentDay, lastDay));
}
```

**Month-End Clamping Examples:**
- Obligation due 31st, February 2026 (28 days) → clamps to 28th
- Obligation due 31st, April 2026 (30 days) → clamps to 30th
- Obligation due 15th, any month → uses 15th (no clamping needed)

**FIXED_TERM Guards:**
- If obligation end date is March 31 and today is April 1, `compute()` returns null (no more notifications)
- If payment day is 31st but obligation ends on the 15th, returns the 15th as the final notification date

**Test coverage exists:** `src/test/java/com/example/finance_hq/obligation/NextDueDateComputerTest.java` validates:
- Recurring payment day in future/past months
- Month-end clamping (Feb 28)
- FIXED_TERM end-date boundaries

#### End-to-End Date Computation Example

**Scenario:** Obligation due on Tuesday, June 3, 2025 (next week from Monday, June 2)

```
TODAY = Monday, 2025-06-02
Obligation payment day = 3 (day of month)
Period = RECURRING
End date = null

NextDueDateComputer.compute(3, 2025-06-02, RECURRING, null)
  → clampToMonth(3, June 2025) = 2025-06-03 (Tuesday)
  → candidate (2025-06-03) is after today (2025-06-02)? YES
  → return 2025-06-03

Scheduler filter logic:
  BusinessDayCalculator.previousBusinessDay(2025-06-03) = 2025-06-02 (Monday)
  Is previousBusinessDay(nextDueDate) == today?
  Is 2025-06-02 == 2025-06-02? YES
  
RESULT: Notification fires on Monday for Tuesday payment ✓
```

---

### Component 3: Email Sending Service

**File:** `src/main/java/com/example/finance_hq/notification/NotificationService.java`

#### Primary Send Method

```java
private void sendGroupedEmailForObligations(User user, List<Obligation> obligations, LocalDate dueDate) {
    int n = obligations.size();
    StringBuilder body = new StringBuilder();
    body.append("You have ").append(n).append(" payment(s) due on ").append(dueDate).append(":\n\n");
    for (Obligation o : obligations) {
        body.append(o.getName()).append(" — ").append(o.getAmount()).append("\n");
    }
    body.append("\nThis is an automated reminder from FinanceHQ.");

    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(fromAddress);
    message.setTo(user.getEmail());
    message.setSubject("FinanceHQ: " + n + " payment(s) due " + dueDate);
    message.setText(body.toString());
    mailSender.send(message);  // ← Uses Spring's JavaMailSender
}
```

**Key points:**
- Groups multiple obligations by (user, due_date) to send one email per user per day
- Uses Spring's `JavaMailSender.send(SimpleMailMessage)`
- No retry logic at send time; failures are caught by caller

#### Configuration

**File:** `src/main/resources/application.properties`

```properties
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=${GMAIL_USERNAME}
spring.mail.password=${GMAIL_APP_PASSWORD}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
```

**Dependency:** `spring-boot-starter-mail` in `pom.xml`

**Important:** Uses environment variables `GMAIL_USERNAME` and `GMAIL_APP_PASSWORD`. Must be set in local profile or deployment environment.

#### Orchestration (Daily Notification Flow)

**File:** `src/main/java/com/example/finance_hq/notification/NotificationService.java:44-76`

```java
public void runDailyNotifications(LocalDate today) {
    List<ObligationService.SchedulerTarget> targets = obligationService.findAllSchedulerTargets(today);
    
    // Filter: only obligations where previousBusinessDay(nextDueDate) == today
    List<ObligationService.SchedulerTarget> dateDue = targets.stream()
            .filter(t -> BusinessDayCalculator.previousBusinessDay(t.nextDueDate()).equals(today))
            .toList();

    // Group by (user, dueDate)
    Map<UserDateKey, List<ObligationService.SchedulerTarget>> byUserAndDate = 
        dateDue.stream().collect(groupingBy(t -> new UserDateKey(t.obligation().getUser(), t.nextDueDate())));

    byUserAndDate.forEach((key, userTargets) -> {
        User user = userTargets.getFirst().obligation().getUser();
        LocalDate dueDate = userTargets.getFirst().nextDueDate();
        try {
            sendGroupedEmail(user, userTargets, dueDate);  // ← Email send
            persistenceService.recordSuccess(userTargets);  // ← DB log write
        } catch (MailException e) {
            log.error("Failed to send notification to {}: {}", user.getEmail(), e.getMessage());
            persistenceService.recordFailure(userTargets);  // ← Fallback: record failure
        }
    });
}
```

**Flow:**
1. Load all obligations with next due dates
2. Filter to those where `previousBusinessDay(nextDueDate) == today`
3. Group by (user, dueDate)
4. For each group:
   - Call `sendGroupedEmail()` (OUTSIDE transaction)
   - On success, call `recordSuccess()` (IN transaction)
   - On MailException, call `recordFailure()` (IN transaction)

---

### Component 4: Notification Log and Deduplication

#### Schema and Constraint

**File:** `src/main/resources/db/migration/V6__create_notification_log_table.sql`

```sql
CREATE TABLE notification_log (
    id UUID PRIMARY KEY,
    obligation_id UUID NOT NULL REFERENCES obligation(id) ON DELETE CASCADE,
    due_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP,
    CONSTRAINT uq_notification_obligation_due UNIQUE (obligation_id, due_date)
);
```

**Unique constraint:** `(obligation_id, due_date)` — only one log entry per (obligation, due date) pair.

**Statuses:** `SENT` or `FAILED`

#### JPA Entity

**File:** `src/main/java/com/example/finance_hq/notification/NotificationLog.java`

Standard entity mapping with:
- ManyToOne to Obligation (LAZY fetch)
- Enum field for status
- Timestamp fields for tracking when sent

#### Persistence Service

**File:** `src/main/java/com/example/finance_hq/notification/NotificationPersistenceService.java`

Three key methods, all `@Transactional`:

**recordSuccess()** (lines 26-35):
```java
@Transactional
public void recordSuccess(List<ObligationService.SchedulerTarget> targets) {
    LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Warsaw"));
    for (ObligationService.SchedulerTarget t : targets) {
        NotificationLog entry = new NotificationLog(t.obligation(), t.nextDueDate(), NotificationStatus.SENT);
        entry.setSentAt(now);
        notificationLogRepository.save(entry);
        decrementIfFixedTerm(t.obligation());
    }
}
```

- Saves one transaction per batch of notifications
- If UNIQUE constraint violation occurs (e.g., duplicate obligation_id + due_date), transaction rolls back
- Email has already been sent (point of no return)

**recordFailure()** (lines 37-43):
```java
@Transactional
public void recordFailure(List<ObligationService.SchedulerTarget> targets) {
    for (ObligationService.SchedulerTarget t : targets) {
        notificationLogRepository.save(
                new NotificationLog(t.obligation(), t.nextDueDate(), NotificationStatus.FAILED));
    }
}
```

- Records failures with status=FAILED
- Same UNIQUE constraint applies; prevents duplicate FAILED rows for same (obligation, date)

**markRetrySuccess()** (lines 47-56):
```java
@Transactional
public void markRetrySuccess(List<NotificationLog> logs) {
    LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Warsaw"));
    for (NotificationLog nl : logs) {
        nl.setStatus(NotificationStatus.SENT);
        nl.setSentAt(now);
        notificationLogRepository.save(nl);
        decrementIfFixedTerm(nl.getObligation());
    }
}
```

- Updates existing FAILED logs to SENT (does not create new rows)
- Safe from duplicates because it modifies, not inserts

#### Repository Queries

**File:** `src/main/java/com/example/finance_hq/notification/NotificationLogRepository.java`

**Dedup check query:**
```java
@Query("SELECT nl.obligation.id FROM NotificationLog nl WHERE nl.dueDate IN :dueDates")
Set<UUID> findAlreadyLoggedObligationIds(@Param("dueDates") Collection<LocalDate> dueDates);
```

- Returns obligation IDs that already have a log entry for given due dates
- Used to filter out re-runs in scheduler

**Retry query:**
```java
@Query("SELECT nl FROM NotificationLog nl JOIN FETCH nl.obligation o JOIN FETCH o.user WHERE nl.status = :status")
List<NotificationLog> findByStatusWithObligationAndUser(@Param("status") NotificationStatus status);
```

- Finds all logs with given status (FAILED or SENT)
- JOIN FETCH eagerly loads obligation and user (prevents LazyInitializationException)
- Used by `retryFailedNotifications()` to find eligible rows

---

### Component 5: Retry Mechanism

**File:** `src/main/java/com/example/finance_hq/notification/NotificationScheduler.java:23-26` and `NotificationService.java:78-97`

#### Scheduled Retry

```java
@Scheduled(fixedDelay = 3_600_000)  // every 1 hour
public void retryFailedNotifications() {
    notificationService.retryFailedNotifications();
}
```

#### Retry Logic

```java
public void retryFailedNotifications() {
    List<NotificationLog> failedLogs = notificationLogRepository.findByStatusWithObligationAndUser(NotificationStatus.FAILED);
    
    Map<UserDateKey, List<NotificationLog>> byUserAndDate = 
        failedLogs.stream().collect(groupingBy(nl -> new UserDateKey(nl.getObligation().getUser(), nl.getDueDate())));

    byUserAndDate.forEach((key, logs) -> {
        User user = key.getUser();
        LocalDate dueDate = key.getDueDate();
        try {
            List<Obligation> obligations = logs.stream().map(NotificationLog::getObligation).toList();
            sendGroupedEmail(user, obligations, dueDate);  // ← Same send method
            persistenceService.markRetrySuccess(logs);     // ← Update status to SENT
        } catch (MailException e) {
            log.error("Failed to retry notification to {}: {}", user.getEmail(), e.getMessage());
            // Retry row stays as FAILED; will be retried again next hour
        }
    });
}
```

**Flow:**
1. Query all logs where status=FAILED
2. Group by (user, dueDate)
3. For each group:
   - Call sendGroupedEmail() (same method as daily)
   - On success: call markRetrySuccess() to update rows to SENT
   - On failure: log error, leave rows as FAILED (retry again in 1 hour)

---

## Critical Issue: Transaction Boundary Vulnerability

### The Risk

**Risk #6 from test-plan.md:** "Notification retry produces duplicate emails — email sends successfully but DB write to notification_log fails; next scheduler run re-sends."

### Root Cause

Email send and log write are **separate transactions**:

```
sendGroupedEmail()              ← OUTSIDE transaction (irreversible)
  ↓ (succeeds, email sent)
persistenceService.recordSuccess()  ← NEW transaction (may fail)
  ↓ (fails → rolls back)
  (email already sent, log write failed)
```

### Failure Scenario

1. **Daily run at 8 AM:**
   - `sendGroupedEmail(user, [oblig_A], 2025-06-03)` succeeds
   - Email delivered to user@example.com
   - `recordSuccess()` transaction begins
   - Attempt to save `NotificationLog(obligation_id=A, due_date=2025-06-03, status=SENT)`
   - **Database error** (network timeout, disk full, etc.)
   - Transaction rolls back; row is NOT saved

2. **Next daily run at 8 AM (24 hours later):**
   - Query `findAlreadyLoggedObligationIds([2025-06-03])`
   - Returns empty (log write failed, row does not exist)
   - Obligation A is **again eligible** for notification
   - `sendGroupedEmail(user, [oblig_A], 2025-06-03)` runs again
   - **Duplicate email sent** to user@example.com
   - `recordSuccess()` succeeds this time; row is saved

### Why UNIQUE Constraint Alone Doesn't Help

The UNIQUE(obligation_id, due_date) constraint prevents **duplicate log rows**, not duplicate **emails**. The constraint enforcement happens at INSERT time; if the insert fails, it's already too late — the email has been sent.

### Why Not Wrap in a Single Transaction?

Wrapping email send in a Spring `@Transactional` method would look like:

```java
@Transactional
public void sendAndLog(...) {
    sendGroupedEmail(...);  // May throw MailException
    persistenceService.recordSuccess(...);  // If send fails, logs never run
}
```

**Problem:** If `sendGroupedEmail()` throws MailException, the transaction rolls back. But the email has already been sent to the SMTP server (asynchronous, irreversible). Rolling back the transaction cannot unsend the email. The current design is actually safer: it acknowledges that email send is irreversible and separates it from the log write, which is reversible.

### How to Prevent Duplicates at Test Time

The only real prevention is **idempotent sends** (check if email was already sent before resending) or **deduplication in the retry query** (query for FAILED rows but exclude if they were already sent once). Current implementation does neither.

**Example idempotent approach:**
```java
public void retryFailedNotifications() {
    List<NotificationLog> failedLogs = notificationLogRepository.findByStatusWithObligationAndUser(NotificationStatus.FAILED);
    
    // BEFORE resending, check if email was actually sent (via external log/tracking)
    // If yes, mark as SENT without resending
    // If no, resend
}
```

Current implementation retries blind; it does not check if the email was already delivered.

---

## Architecture Insights

### Obligations and Notifications

- **Obligation** is the domain object representing a payment due
- **NotificationLog** is the audit trail — one row per (obligation, due_date) pair notified
- **FIXED_TERM obligations** have an endDate; notifications stop after the end date
- **RECURRING obligations** have no end date; notifications continue indefinitely

### Scheduler Design

- **Stateless**: Scheduler queries all obligations every day and computes eligibility on the fly
- **No persistent state** beyond the log (no "last checked" timestamp)
- **Simple but inefficient**: O(n) for n obligations per day (not filtered at DB level)

### Timezone Strategy

- **Hardcoded to Europe/Warsaw** in three places
- Single scheduler instance assumes all users operate in the same timezone
- Multi-timezone support would require per-user timezone config or per-obligation timezone

### Deduplication Strategy (Current)

1. **Constraint-based**: UNIQUE(obligation_id, due_date) prevents duplicate log rows
2. **Query-based**: `findAlreadyLoggedObligationIds()` filters already-logged obligations
3. **Retry-based**: Hourly retry of FAILED rows via status field

**Gap**: No idempotency check — retry query does not verify if email was already sent (only if log row exists).

---

## Code References Summary

| Component | File | Lines | Purpose |
|-----------|------|-------|---------|
| Scheduler trigger | `notification/NotificationScheduler.java` | 18–26 | @Scheduled cron + retry |
| Business logic | `notification/NotificationService.java` | 44–97 | Daily run + retry orchestration |
| Email send | `notification/NotificationService.java` | 104–119 | `sendGroupedEmailForObligations()` |
| Persistence | `notification/NotificationPersistenceService.java` | 26–56 | `recordSuccess()`, `recordFailure()`, `markRetrySuccess()` |
| Business-day calc | `notification/BusinessDayCalculator.java` | — | `previousBusinessDay(LocalDate)` |
| Date computation | `obligation/NextDueDateComputer.java` | — | `compute(int, LocalDate, ObligationPeriod, LocalDate)` |
| Obligation query | `obligation/ObligationService.java` | 75–81 | `findAllSchedulerTargets(LocalDate)` |
| Obligation entity | `obligation/Obligation.java` | — | JPA entity: id, paymentDay, period, endDate, etc. |
| Notification log | `notification/NotificationLog.java` | — | JPA entity + UNIQUE constraint |
| Repository (log) | `notification/NotificationLogRepository.java` | 15–19 | `findAlreadyLoggedObligationIds()`, `findByStatusWithObligationAndUser()` |
| DB schema (log) | `db/migration/V6__create_notification_log_table.sql` | 8 | UNIQUE(obligation_id, due_date) |
| Tests (business-day) | `notification/BusinessDayCalculatorTest.java` | — | Weekday transitions |
| Tests (date logic) | `obligation/NextDueDateComputerTest.java` | — | Month-end clamping, FIXED_TERM boundaries |
| Tests (dedup) | `notification/NotificationLogRepositoryTest.java` | 68–75 | UNIQUE constraint prevents insert |

---

## Risk-Response Grounding (from test-plan.md §2)

### Risk #1: Notification email fails to arrive

**What would prove protection:**
- Obligation due 1 biz day from now triggers scheduler; email send attempted; notification_log records SUCCESS
- Obligation due further out does NOT trigger

**Context found:**
- ✓ Scheduler query: `findAllSchedulerTargets()` loads all obligations (no date filter at DB level)
- ✓ Eligibility filter: `previousBusinessDay(nextDueDate) == today` applied in memory
- ✓ Email send: `JavaMailSender.send()` with Gmail SMTP config
- ✓ Log recording: `recordSuccess()` saves NotificationLog with status=SENT and sentAt timestamp

**Must challenge:**
- "Scheduler ran" does not mean "email arrived." → Need to verify SMTP delivery (not just send call)
- "Works for 1 obligation" does not mean "works for 20 with same due date" → Batch grouping by (user, dueDate) handles this

### Risk #2: Scheduler computes wrong notification date

**What would prove protection:**
- Monday due: Friday notify
- Tuesday due: Monday notify
- 31st in short month: correct clamp + biz day
- FIXED_TERM past endDate: no notification

**Context found:**
- ✓ Business-day calc: `previousBusinessDay(date)` correctly skips Sat/Sun
- ✓ Month-end clamp: `clampToMonth(paymentDay, month)` uses `Math.min(paymentDay, lastDay)`
- ✓ FIXED_TERM guard: Returns null if past endDate; clamped candidate to endDate if candidate > endDate
- ✓ Test coverage: `NextDueDateComputerTest.java` validates month-end and FIXED_TERM scenarios

**Must challenge:**
- "Business-day calculator is correct" does not mean "scheduler uses it correctly." → Verified: scheduler uses `previousBusinessDay()` in filter
- "Scheduler uses correct timezone" → **ISSUE**: Hardcoded to Europe/Warsaw only
- Missing public holiday support → Weekends are skipped, but holidays are not

### Risk #6: Notification retry produces duplicate emails

**What would prove protection:**
- If email sends but notification_log write fails, next scheduler run re-queries but deduplication prevents second email for same obligation + due_date

**Context found:**
- ✓ Transaction boundary: Email send OUTSIDE transaction, log write IN separate transaction
- ✓ UNIQUE constraint: `(obligation_id, due_date)` prevents duplicate rows
- ✗ **CRITICAL GAP**: No check if email was already sent; retry query only checks if log row exists
- ✗ If log write fails after send, email is already delivered; next retry will send again (duplicate)

**Must challenge:**
- "UNIQUE constraint exists" does not mean "duplicates prevented." → Constraint prevents duplicate rows, not duplicate emails
- Retry query should verify idempotency (was email already delivered?) → Currently does not

---

## Open Questions and Follow-up Research

1. **Public holiday support:** Should the business-day calculator include a holiday calendar (e.g., Polish holidays)? Currently missing.

2. **Multi-timezone support:** The hardcoded Europe/Warsaw timezone is a business assumption. Should support different timezones per user or per obligation?

3. **Idempotency tracking:** To prevent duplicate sends on retry, should there be an external email tracking service (e.g., "was this email already sent to this recipient for this obligation + date?")? Or rely on SMTP idempotency?

4. **Performance at scale:** `findAllSchedulerTargets()` loads all obligations in memory. For 10k+ obligations, this becomes slow. Should pagination or DB-level filtering be added?

5. **Test environment setup:** For integration tests, do we need:
   - Real PostgreSQL via Testcontainers (for UNIQUE constraint testing)?
   - Mock JavaMailSender or stub SMTP server?
   - Flyway migration applied to test DB?

---

## Summary for Phase 1 Plan

**Phase 1 must prove:**

1. **Unit layer** (date computation):
   - BusinessDayCalculator correctly skips weekends
   - NextDueDateComputer correctly clamps month-end dates
   - Both work together for edge cases (31st → 28th, then apply business day)

2. **Integration layer** (scheduler → email → log):
   - Obligation due 1 biz day from now triggers scheduler
   - Email is sent (mock JavaMailSender, verify send() called)
   - NotificationLog records status=SENT with correct timestamp
   - No duplicate emails for multiple obligations with same due date (grouping works)

3. **Integration failure layer** (transaction boundary):
   - Email sends successfully (mock succeeds)
   - NotificationLog save throws exception (simulate DB failure)
   - Next scheduler run resends email (duplicate scenario)
   - **Test assertion:** Verify duplicate email is sent (document the risk) so we can later add idempotency check

**All three components (scheduler, date logic, email) exist and are testable. No major refactoring needed to begin Phase 1 testing.**
