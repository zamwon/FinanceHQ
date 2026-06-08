---
change_id: testing-frontend-production-parity
title: Phase 3 — Test frontend production parity
status: archived
created: 2026-06-03
updated: 2026-06-08
archived_at: 2026-06-08T12:28:08Z

---

## Notes

Rollout Phase 3 of context/foundation/test-plan.md: "Frontend production parity".

Risks covered: #5 (Angular prod build diverges from dev), #7 (auth token refresh race condition logs user out).

Test types planned: e2e smoke (Playwright against prod JAR) + unit (auth interceptor concurrent-401 scenario).

Risk response intent:
- R5 — prove Angular prod build served by Spring Boot renders login, allows login, shows dashboard, navigates to obligations — same flow as `ng serve`. All lazy-loaded bundles must resolve, deep-link SPA forwarding must work, Tailwind/spartan.ui styling must render.
- R7 — prove two concurrent 401s trigger only one refresh-token request; both original requests retry with new token; session continues. Prove that if refresh itself fails, queued requests fail gracefully (not hang indefinitely).
