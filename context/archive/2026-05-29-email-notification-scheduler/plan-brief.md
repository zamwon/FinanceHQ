# Email Notification Scheduler — Plan Brief

> Full plan: `context/changes/email-notification-scheduler/plan.md`

## What & Why

Implement FR-007: the system sends one email per user the business day before each obligation's next payment date. This is the north-star feature — "a missed notification means the product is broken." Without it, the obligation tracker is just a list. With it, the product removes the manual monitoring burden for an active investor who keeps funds deployed and can't rely on checking a list every day.

## Starting Point

The Obligation domain is complete: entity, service, and `NextDueDateComputer` all exist and produce correct next-due dates for both RECURRING and FIXED_TERM periods. `User.getEmail()` provides the recipient address. Zero email or scheduling infrastructure exists — no `spring-boot-starter-mail`, no `@EnableScheduling`, no SMTP config in `application.properties`. Last Flyway migration: V5.

## Desired End State

A daily email arrives at 08:00 Warsaw time listing all obligations due the next business day. Sends that fail are tracked in a `notification_log` table and retried every hour. `remainingPayments` on FIXED_TERM obligations decrements transactionally when a notification is confirmed sent. The system is observable via the `notification_log` table with no frontend changes required.

## Key Decisions Made

| Decision | Choice | Why | Source |
|---|---|---|---|
| Business day definition | Friday before Monday/weekend due dates | "1 business day" = working day; Sunday email can't prompt action before a Monday deadline | Plan |
| SMTP provider | Gmail App Password | No new accounts; single-user personal tool; 500/day limit irrelevant | Plan |
| Deduplication | `notification_log(obligation_id, due_date)` UNIQUE | Idempotent, audit-able, survives restarts — no double-send risk on redeploy | Plan |
| Error handling | FAILED row in notification_log + hourly retry | Natural retry via DB state; no separate queue needed; FAILED rows are the retry work queue | Plan |
| Email format | One grouped plain-text email per user per day | Inbox-friendly; simpler than per-obligation; failure affects all-or-nothing per user | Plan |
| Scheduler implementation | `@Scheduled` + `@EnableScheduling` | `notification_log` already provides retry state; Quartz is overkill for a single-user tool | Plan |
| `remainingPayments` decrement | On SENT transition, same `@Transactional` as log write | Exactly-once semantics; decrement is tied to confirmed send, not the send attempt | Plan |
| Frontend scope | Backend only | The email IS the user-facing feature for v0.1 | Plan |

## Scope

**In scope:** `spring-boot-starter-mail`, `@EnableScheduling`, Flyway V6 (`notification_log` table), `NotificationStatus` enum, `NotificationLog` entity + repo, `BusinessDayCalculator`, `NotificationService` (daily run + retry), `NotificationScheduler`, Gmail SMTP config with Railway env var placeholders, `Obligation.setRemainingPayments()`, `ObligationService.findAllSchedulerTargets()`, unit + integration tests.

**Out of scope:** Polish bank holidays, HTML email templates, SMS/push, frontend notification badge, Quartz, per-obligation notification preferences.

## Architecture / Approach

Two `@Scheduled` jobs: a daily cron at `0 0 8 * * *` (zone: `Europe/Warsaw`) and an hourly `fixedDelay` retry. `NotificationService` is the logic hub — it calls `ObligationService.findAllSchedulerTargets(today)` (the only cross-package entry point to the obligation domain), filters to obligations whose `previousBusinessDay(nextDueDate) == today`, groups by user, sends one `SimpleMailMessage` per user via `JavaMailSender`, then writes `notification_log` rows inside `@Transactional`. Email send is deliberately *outside* any transaction — the DB write is the commit, not the send.

`NextDueDateComputer` stays package-private. `NotificationService` never touches it directly — `ObligationService.findAllSchedulerTargets()` acts as the bridge, computing and filtering targets inside the `obligation` package.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Infrastructure | `spring-boot-starter-mail`, `@EnableScheduling`, V6 migration, `NotificationLog` entity + repo, SMTP config | V6 must apply cleanly; empty `GMAIL_USERNAME` fallback must not crash startup |
| 2. Business Logic | `BusinessDayCalculator`, `ObligationService.findAllSchedulerTargets()`, `NotificationService` with daily + retry paths | Transaction boundary: send outside tx, log write inside; JOIN FETCH on retry to avoid `LazyInitializationException` |
| 3. Scheduler | `NotificationScheduler` with two `@Scheduled` methods | Timezone: explicit `ZoneId.of("Europe/Warsaw")` in `LocalDate.now()` call; `fixedDelay` not `fixedRate` for retry |
| 4. Tests | `BusinessDayCalculatorTest`, `NotificationServiceTest`, `NotificationLogRepositoryTest` | TC 2.x artifact names; `saveAndFlush()` for UNIQUE constraint test; `@Transactional` on `@SpringBootTest` |

**Prerequisites:** S-02 (add-and-list-obligations) must be complete and deployed — the Obligation entity and `NextDueDateComputer` are core inputs.
**Estimated effort:** ~2–3 sessions across 4 phases.

## Open Risks & Assumptions

- Gmail App Password is a manual setup step outside this plan — must be created in Google Account → Security → 2-Step Verification → App passwords before the first production deploy.
- Empty `GMAIL_USERNAME`/`GMAIL_APP_PASSWORD` fallbacks are safe on startup but will generate ERROR log lines for failed sends during local development — acceptable noise.
- DST handling is delegated to `@Scheduled(zone = "Europe/Warsaw")` — no manual UTC offset math needed; Spring handles DST transitions.
- Single-user scale assumed: `repository.findAll()` in the daily scheduler loads all obligations in memory. Fine now; revisit if multi-user is introduced.

## Success Criteria (Summary)

- Email arrives in inbox at 08:00 Warsaw on the business day before each obligation's due date
- No duplicate emails — a second scheduler run on the same day skips already-sent obligations
- FAILED sends are retried automatically within 1 hour and resolve to SENT on credential restore
