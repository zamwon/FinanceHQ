# Resend Email Integration — Plan Brief

> Full plan: `context/changes/resend-email-integration/plan.md`

## What & Why

Replace the Spring Boot `JavaMailSender`/SMTP stack with the Resend HTTP API. Railway blocks outbound TCP on ports 25/465/587 for non-Pro plans, which means every production email send fails with `MailConnectException` before a single byte reaches Gmail. Switching to Resend's HTTP API routes delivery over HTTPS — unaffected by Railway's SMTP block.

## Starting Point

`NotificationService` injects `JavaMailSender` and sends plain-text emails via `SimpleMailMessage`. The app has a working retry mechanism (hourly re-attempt of `FAILED` log entries) but both the daily send and every retry fail at the TCP connection step.

## Desired End State

Emails are delivered to `blazej.karnecki@gmail.com` via Resend's API. The June 10 obligation triggers a real email at 08:00 Warsaw time. The Resend dashboard shows status "Delivered" and `notification_log` rows transition from `FAILED` to `SENT`.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Email delivery mechanism | Resend Java SDK | Official SDK gives typed objects and built-in error handling; no HTTP boilerplate | Plan |
| Spring Mail removal | Remove entirely | Dead dependency and config signals confusion; rollback is cheap (re-add dep) | Plan |
| Test seam | Extract `EmailSender` interface | Matches the project's existing mock pattern — no new test dependencies needed | Plan |
| Error surface | Wrap `ResendException` in `MailException` | Existing `catch (MailException)` blocks in `NotificationService` stay unchanged | Plan |
| Sender address | `blazej.karnecki@gmail.com` via Resend | Consistent with the current setup; requires one-time email verification in Resend dashboard | Plan |
| Local dev | Real Resend test key in `application-local.yml` | Identical code path locally and prod; keeps the notification pipeline testable end-to-end | Plan |

## Scope

**In scope:**
- `pom.xml` — remove `spring-boot-starter-mail`, add `resend-java`
- New `EmailSender` interface + `ResendEmailSender` service
- `NotificationService` — swap constructor injection, simplify send method
- `application.properties` + `application-local.yml` — swap mail config for Resend config
- Update 2 test files; add 1 new unit test for error mapping
- Railway env vars: add `RESEND_API_KEY`, delete `GMAIL_USERNAME` + `GMAIL_APP_PASSWORD`

**Out of scope:**
- DB schema changes
- Scheduler timing or retry logic changes
- Custom sending domain setup
- Frontend changes

## Architecture / Approach

`NotificationService` now depends on `EmailSender` (interface), not `JavaMailSender` (Spring class). `ResendEmailSender` implements the interface and is the only component that knows about Resend. Tests mock the interface with Mockito — no WireMock or HTTP-level stubs needed. The `fromAddress` responsibility moves from `NotificationService` into `ResendEmailSender`'s config.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Core Swap | `EmailSender` interface + `ResendEmailSender` + config wired up; app starts | `ResendException` class path or SDK API shape differs from expected |
| 2. Test Updates | Full test suite green; no `JavaMailSender` reference remains | Resend SDK internals may be hard to mock — may need constructor injection shim |
| 3. Deploy & Verify | Real email received; Railway logs clean | Resend sender verification email must be actioned before deploy |

**Prerequisites:** Resend account created; `blazej.karnecki@gmail.com` verified as sender in Resend dashboard before Phase 3
**Estimated effort:** ~1 session across 3 phases

## Open Risks & Assumptions

- Resend Java SDK Maven coordinates and API shape assumed from public docs — verify `resend-java` version on Maven Central before adding to `pom.xml`
- Resend test keys only deliver to verified addresses; local smoke tests require the sender email to be verified in the Resend dashboard first
- Railway `notification_log` may have lingering `FAILED` rows for June 9 — the hourly retry will attempt to send those via Resend once deployed; this is the intended behavior

## Success Criteria (Summary)

- `./mvnw test` passes with zero failures after Phase 2
- `blazej.karnecki@gmail.com` receives the June 10 obligation reminder email
- No `MailConnectException` appears in Railway logs after deploy
