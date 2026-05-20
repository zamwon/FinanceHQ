---
starter_id: spring
package_manager: maven
project_name: finance-hq
hints:
  language_family: java
  team_size: solo
  deployment_target: fly
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: false
  has_background_jobs: true
  frontend: angular
---

## Why this stack

Solo developer shipping a personal finance obligation tracker in 3 weeks (after-hours).
The PRD requires auth (FR-001, FR-002) and scheduled email notifications one business day before each due date (FR-007), making background job support a must-have.
Spring Boot is the recommended default for `(web-app, java)` and passes all four agent-friendly quality gates — typed (Java's static type system),
convention-based (Spring's auto-configuration and opinionated project layout), well-represented in training data, and thoroughly documented.
The auth requirement is met by Spring Security; the scheduled notification need maps naturally to Spring's `@Scheduled` or a Quartz-backed cron job.
Angular is added as the frontend layer, scaffolded separately in `src/main/frontend/` and served as static assets by Spring Boot in production — a standard enterprise pairing
that keeps the backend API and SPA in a single repo. Fly is the starter's default deployment target;
GitHub Actions with auto-deploy-on-merge is the standard CI shape for a solo project with a hard July 2026 deadline.
clScaffolding confidence for Spring Boot is verified.
