---
project: FinanceHQ
version: 1
status: draft
created: 2026-05-25
updated: 2026-05-25
prd_version: 1
main_goal: speed
top_blocker: capacity
---

# Roadmap: FinanceHQ

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Vision recap

FinanceHQ solves a gap that standard fintech apps ignore: active investors who pre-deploy most of their funds can't rely on current-account automation to handle payment deadlines. The product tracks financial obligations and fires an email notification one business day before each due date — removing the manual monitoring burden. v0.1 is a personal tool for a single user; the core bet is that reliable, timely notifications are the entire value.

## North star

**S-04: email-notification-scheduler — system sends an email one business day before each obligation's due date.**

> North star: the smallest end-to-end slice whose successful delivery proves the core product hypothesis — placed as early as Prerequisites allow because everything else only matters if this works. For FinanceHQ, the north star is the notification delivery proof: if the scheduler fires on the right date and the email arrives, the product's core promise is validated. PRD Primary Success Criterion: *"Notifications reliably arrive — a missed notification means the product is broken."*

## At a glance

| ID   | Change ID                     | Outcome (user can … / foundation …)                                                                 | Prerequisites    | PRD refs                          | Status   |
|------|-------------------------------|-----------------------------------------------------------------------------------------------------|------------------|-----------------------------------|----------|
| F-01 | f-01-data-persistence-scaffold | (foundation) database connectivity and schema migration tooling wired; base user table seeded       | —                | NFR (data persists reliably), NFR (encrypted at rest) | done     |
| F-02 | auth-scaffold                 | (foundation) registration and login REST endpoints secured; user identity issued on every protected route | F-01         | FR-001, FR-002, Access Control    | proposed |
| F-03 | angular-spa-scaffold          | (foundation) Angular SPA scaffolded with routing, HTTP client, and auth guard; served by backend in production | —       | NFR (latest two major browser versions) | ready |
| S-01 | register-and-login            | user can register with email and password and log in via the web UI without errors                  | F-01, F-02, F-03 | FR-001, FR-002, US-01             | proposed |
| S-02 | add-and-list-obligations      | user can add an obligation and see it immediately in their obligation list                          | S-01             | FR-003, FR-004, US-01             | proposed |
| S-03 | edit-and-delete-obligations   | user can edit an obligation's amount and payment date, and delete it after a confirmation dialog     | S-02             | FR-005, FR-006                    | proposed |
| S-04 | email-notification-scheduler  | system sends an email one business day before each obligation's next due date, reliably             | S-02             | FR-007, US-01                     | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme                         | Chain                                                                     | Note                                                                                             |
|--------|-------------------------------|---------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| A      | Core value path               | `F-01` → `F-02` → `S-01` → `S-02` → `S-04`                              | North star delivery chain; every item here is on the critical path to first validated notification. |
| B      | Parallel scaffold + hardening | `F-03` → `S-01` (joins A) / `S-03` (after `S-02`, parallel with `S-04`) | F-03 is a parallel root that unblocks S-01 alongside F-02; S-03 runs alongside the north star.  |

## Baseline

What's already in place in the codebase as of 2026-05-25 (auto-researched + user-confirmed). Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** absent — no Angular SPA; `src/main/frontend/` directory does not exist
- **Backend / API:** partial — `FinanceHqApplication.java` entry point + one stub `GET /hello`; no domain controllers
- **Data:** absent — no JPA entities, no migration tooling, no datasource dependencies in `pom.xml`
- **Auth:** absent — no Spring Security, no login endpoints, no token handling
- **Deploy / infra:** present — `Dockerfile`, `railway.toml`, `.github/workflows/deploy.yml` all wired and operational
- **Observability:** partial — Spring Actuator `/health` endpoint only; no structured logging or error tracking configured

## Foundations

### F-01: Data persistence scaffold

- **Outcome:** (foundation) database connectivity and schema migration tooling wired; base user table seeded and migrations running against PostgreSQL in all environments.
- **Change ID:** f-01-data-persistence-scaffold
- **PRD refs:** NFR "Obligations saved persist reliably — no data loss or corruption", NFR "User passwords and obligation data are encrypted at rest and in transit"
- **Unlocks:** F-02 (auth layer needs a user table to persist identities), S-02 (obligation storage requires schema and connectivity in place)
- **Prerequisites:** —
- **Parallel with:** F-03
- **Blockers:** —
- **Unknowns:** — (Railway IPv4 / Supabase IPv6 mismatch is a known pre-deploy step documented in `context/foundation/infrastructure.md`; must use Session Pooler host — apply during planning, not a blocking unknown)
- **Risk:** Sequenced first because every slice above the data layer depends on it. The Railway + Supabase Session Pooler wiring is a documented failure mode that must be applied before the first deploy; missing it produces a silent connection failure.
- **Status:** done

---

### F-02: Auth scaffold

- **Outcome:** (foundation) registration and login REST endpoints secured; user identity issued and verified on every protected route.
- **Change ID:** auth-scaffold
- **PRD refs:** FR-001, FR-002, Access Control section ("email + password login; single-user model in MVP")
- **Unlocks:** S-01 (register and login flow requires working auth endpoints and identity verification in the UI)
- **Prerequisites:** F-01
- **Parallel with:** F-03
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Sequenced immediately after F-01 because auth is the identity layer for all obligation data. Getting the session or token contract wrong here cascades into every downstream slice and requires breaking refactors.
- **Status:** proposed

---

### F-03: Angular SPA scaffold

- **Outcome:** (foundation) Angular SPA scaffolded with routing, HTTP client, and auth guard; served as static assets by the backend in production builds.
- **Change ID:** angular-spa-scaffold
- **PRD refs:** NFR "The web app is usable on the latest two major versions of Chrome, Firefox, Safari, and Edge"
- **Unlocks:** S-01 (register and login UI), S-02 (obligation list and add-form UI), S-03 (edit and delete UI)
- **Prerequisites:** —
- **Parallel with:** F-01
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Sequenced in parallel with F-01 to maximise throughput under `top_blocker: capacity`. The SPA needs the auth API contract (produced by F-02) to finalise the login flow — start with routing and stub screens; wire auth endpoints once F-02 ships.
- **Status:** ready

---

## Slices

### S-01: Register and login

- **Outcome:** user can register with an email address and password and log in via the web UI, without errors.
- **Change ID:** register-and-login
- **PRD refs:** FR-001, FR-002, US-01 (acceptance criteria: "Registration completes without errors (email validation, password requirements)", "Login succeeds with registered credentials")
- **Prerequisites:** F-01, F-02, F-03
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** First user-visible slice; depends on all three foundations being complete. If any foundation is delayed, this is the first item to slip. Treat as the join point for Stream A and Stream B work.
- **Status:** proposed

---

### S-02: Add obligation and view list

- **Outcome:** user can add an obligation (amount, period, payment date, category) and see it immediately in their obligation list.
- **Change ID:** add-and-list-obligations
- **PRD refs:** FR-003, FR-004, US-01 (acceptance criteria: "Obligation saves with all required fields", "Obligation appears in the user's list immediately after creation")
- **Prerequisites:** S-01
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Gateway slice for the north star. Both S-03 (edit/delete) and S-04 (notifications) depend on obligation data existing. This slice must ship before either can be planned or executed; it is the single highest-leverage item after the foundations.
- **Status:** proposed

---

### S-03: Edit and delete obligations

- **Outcome:** user can edit an obligation's amount and payment date, and delete an obligation after confirming in a dialog.
- **Change ID:** edit-and-delete-obligations
- **PRD refs:** FR-005 ("edit amount and payment date only; period and category cannot be edited; user must delete and re-add to change type"), FR-006 ("deletion requires a confirmation dialog to prevent accidental removal")
- **Prerequisites:** S-02
- **Parallel with:** S-04
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Runs in parallel with S-04 (north star) under `main_goal: speed`. Lower-value than S-04 for the core hypothesis; if capacity forces a choice between the two parallel tracks, S-04 ships first.
- **Status:** proposed

---

### S-04: Email notification scheduler

- **Outcome:** system automatically sends an email one business day before each obligation's next due date; notifications require no manual action and are reliably delivered.
- **Change ID:** email-notification-scheduler
- **PRD refs:** FR-007, US-01 (acceptance criteria: "Notification arrives exactly 1 business day before the payment date")
- **Prerequisites:** S-02
- **Parallel with:** S-03
- **Blockers:** —
- **Unknowns:**
  - "1 business day" vs "1 calendar day" — PRD Success Criteria and US-01 acceptance criteria say "1 business day before"; PRD Business Logic says "1 day before". If a payment is due on Monday, does the notification fire on Friday or Sunday? — Owner: user. Block: no (planning can proceed with the default of "skip back over weekends for due dates that fall Monday–Wednesday; notify the preceding Friday"; confirm during `/10x-plan email-notification-scheduler`).
  - Outbound email provider / SMTP configuration — which mail service will handle delivery? — Owner: user. Block: no (any SMTP-compatible provider works; confirm the credential wiring during planning).
- **Risk:** North star slice. If the scheduler or email delivery is unreliable, the product's primary value is broken. PRD NFR: "All notifications arrive exactly 1 day before the payment deadline with no delays or missed messages." Invest care in the scheduling logic and delivery reliability even under `main_goal: speed`.
- **Status:** proposed

---

## Backlog Handoff

| Roadmap ID | Change ID                     | Suggested issue title                                         | Ready for `/10x-plan` | Notes                                                                  |
|------------|-------------------------------|---------------------------------------------------------------|-----------------------|------------------------------------------------------------------------|
| F-01       | data-persistence-scaffold     | Set up PostgreSQL connectivity and schema migration tooling   | yes                   | Run `/10x-plan data-persistence-scaffold`; apply Supabase Session Pooler URL per `infrastructure.md` |
| F-02       | auth-scaffold                 | Implement registration and login REST endpoints with auth     | no                    | Wait for F-01 to complete                                              |
| F-03       | angular-spa-scaffold          | Scaffold Angular SPA with routing, HTTP client, and auth guard | yes                  | Run `/10x-plan angular-spa-scaffold`; parallel with F-01               |
| S-01       | register-and-login            | Build register and login UI flow end-to-end                   | no                    | Wait for F-01, F-02, F-03                                              |
| S-02       | add-and-list-obligations      | Build add-obligation form and obligation list view            | no                    | Wait for S-01                                                          |
| S-03       | edit-and-delete-obligations   | Build edit and delete obligation flows with confirmation      | no                    | Wait for S-02                                                          |
| S-04       | email-notification-scheduler  | Build scheduled email notification job                        | no                    | Wait for S-02; resolve business-day vs calendar-day rule during planning |

## Open Roadmap Questions

1. **"1 business day" vs "1 calendar day" notification timing** — PRD Success Criteria and US-01 acceptance criteria say "1 business day before"; PRD Business Logic says "1 day before". If a payment is due on Monday, does the notification fire on Friday or Sunday? — Owner: user. Block: S-04 planning only (not blocking now; default of "skip back over weekends" applies unless overridden during `/10x-plan email-notification-scheduler`).

## Parked

- **Mobile app (iOS/Android)** — Why parked: PRD §Non-Goals "v0.1 is web-only (browsers). iOS/Android apps deferred to v1.1 or later."
- **Family / multi-user features** — Why parked: PRD §Non-Goals "v0.1 is single-user only. Family sharing and shared budgets deferred."
- **AI cost-optimisation and investment recommendations** — Why parked: PRD §Non-Goals "explicitly v2, not v0.1 or v1."
- **Expense and income tracking** — Why parked: PRD §Non-Goals "v0.1 handles obligations only. Expense and income logging deferred to v1.1."
- **Bank integrations** — Why parked: PRD §Non-Goals "manual entry only. API integrations with banks deferred to future versions."
- **SMS / push notifications** — Why parked: PRD §FR-007 Socrates refinement "email-only notifications for v0.1. SMS/push deferred to v1.1 once email channel is proven."
- **Observability hardening (structured logging, error tracking)** — Why parked: not a PRD must-have; Actuator `/health` is sufficient for MVP; defer to v1.1.

## Done

- **F-01: (foundation) database connectivity and schema migration tooling wired; base user table seeded** — Archived 2026-05-25 → `context/archive/2026-05-25-f-01-data-persistence-scaffold/`. Lesson: —.
