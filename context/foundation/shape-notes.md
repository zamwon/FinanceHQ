---
project: "FinanceHQ"
context_type: greenfield
product_type: web-app
target_scale:
  users: small
created: 2026-05-18
updated: 2026-05-18
timeline_budget:
  mvp_weeks: 3
  hard_deadline: 2026-07-04
  after_hours_only: true
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "Pain category"
      decision: "Workflow friction + decision paralysis — repetitive manual tracking combined with uncertainty over where surplus cash should go."
    - topic: "Insight"
      decision: "Most fintech ignores the invest-and-automate tension. Standard apps assume money stays in current account; this solves for active investors who keep funds deployed."
    - topic: "Primary persona scope"
      decision: "Building for yourself first as proof-of-concept."
    - topic: "Auth method"
      decision: "Email + password login."
    - topic: "User roles"
      decision: "Flat model — single user with full access. Multi-user family features deferred."
  frs_drafted: 7
  quality_check_status: accepted
---

## Vision & Problem Statement

Managing personal finances at an Excel level is inefficient for today's capabilities. The core pain is dual:
manually tracking payment obligations and deadlines is tedious and error-prone, and deciding where surplus cash should go—invest vs. pay bills vs. keep liquid—requires guesswork.
Standard fintech apps assume users keep money in their current account for automation; this doesn't work when you actively invest and pre-deploy most funds.

The insight: fintech has ignored the tension between automated payment scheduling and active investment management.
A person who wants to maximize returns can't keep funds liquid in their checking account, yet wants to avoid missing payment deadlines.
No product solves this—combining cash-flow planning with investment recommendations tied to personal obligations and macro conditions is unexplored.

## User & Persona

**Primary: You** — an individual investor who actively manages finances, keeps most funds deployed in investments to maximize returns,
and needs to track obligations and expenses without manual intervention. You face the moment of uncertainty when deciding where new cash should go:
reinvest, pay upcoming bills, or optimize spending. You want notifications one business day before each obligation comes due so you can ensure funds are available.

## Success Criteria

### Primary
- User can add obligations (type: recurring or fixed term; category: TOP/HIGH/LOW; timeframe, amount, payment day) and receive a notification one business day before each 
  payment is due.
- Notifications reliably arrive — a missed notification means the product is broken.

### Secondary
- User can view a list of upcoming obligations.

### Guardrails
- Data persists correctly — obligations added remain in the system.
- Notifications must be dependable (no timeouts, no lost messages).

**Timeline:** 3 weeks of after-hours work (v0.1 scope). Future versions (v1.1: expense/income/dashboard; v2: AI suggestions) are deferred.

## User Stories

### US-01: New user adds their first obligation and receives a notification

- **Given** a new user with an email address
- **When** they register, log in, add their first obligation (amount: $500, period: recurring monthly, payment date: 15th, category: rent)
- **Then** they see the obligation in their list, and 1 day before the 15th they receive a notification

#### Acceptance Criteria
- Registration completes without errors (email validation, password requirements)
- Login succeeds with registered credentials
- Obligation saves with all required fields
- Obligation appears in the user's list immediately after creation
- Notification arrives exactly 1 business day before the payment date

## Functional Requirements

- FR-001: User can register for an account. Priority: must-have
  > Socrates: Counter-argument considered (skip auth / use passwordless). Resolution: kept; email + password login is core identity.

- FR-002: User can login to their account. Priority: must-have
  > Socrates: Counter-argument considered (session persistence only / PIN). Resolution: kept; email + password login required.

- FR-003: User can add an obligation (amount, period: fixed or open-ended, payment date, category). Priority: must-have
  > Socrates: Counter-argument considered (drop category / simplify period / make period optional). Resolution: kept; all fields needed for obligation clarity.

- FR-004: User can view a list of all their obligations with upcoming due dates. Priority: must-have
  > Socrates: Counter-argument considered (skip list view / show only upcoming). Resolution: kept; users need immediate visibility of what they added.

- FR-005: User can edit an obligation. Priority: must-have
  > Socrates: Refined decision — edit amount and payment date only. Period and category cannot be edited; user must delete and re-add to change type.

- FR-006: User can delete an obligation. Priority: must-have
  > Socrates: Refined decision — deletion requires a confirmation dialog to prevent accidental removal.

- FR-007: System sends a notification 1 business day before each obligation is due. Priority: must-have
  > Socrates: Refined decision — email-only notifications for v0.1. SMS/push notifications deferred to v1.1 once email channel is proven.

## Business Logic

The system automatically notifies users 1 day before each obligation is due, ensuring no payment deadline is missed.

In v0.1, the app stores obligations (amount, period, payment date, category) and applies a single rule: compute the date 1 day before each obligation's next due date,
and send a notification on that date.
The notification is the core value — it transforms obligation tracking from manual (user remembers to check) to automatic (app ensures awareness).
This works because most payment deadlines are predictable (fixed dates or recurring cycles), so the app can calculate them deterministically.

## Non-Functional Requirements

- All notifications arrive exactly 1 day before the payment deadline with no delays or missed messages.
- User passwords and obligation data are encrypted at rest and in transit. No unauthorized access.
- Web app runs on modern browsers: Chrome, Firefox, Safari, Edge (latest two major versions).
- Obligations saved persist reliably — no data loss or corruption.

## Access Control

Email + password login. Single-user model in MVP — whoever logs in has full access to all obligations, expenses, income, and recommendations.
No role separation at launch. Multi-user / family-sharing features are deferred to a future version if needed.

## Non-Goals

- **No mobile app** — v0.1 is web-only (browsers). iOS/Android apps deferred to v1.1 or later.
- **No family/multi-user features** — v0.1 is single-user only. Family sharing and shared budgets deferred.
- **No AI recommendations** — cost optimization and investment suggestions are explicitly v2, not v0.1 or v1.
- **No expense/income tracking** — v0.1 handles obligations only. Expense and income logging deferred to v1.1.
- **No bank integrations** — manual entry only. API integrations with banks deferred to future versions.

## Open Questions

(To be filled as gaps surface.)
