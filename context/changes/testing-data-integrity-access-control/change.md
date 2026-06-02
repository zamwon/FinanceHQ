---
change_id: testing-data-integrity-access-control
title: Phase 2 — Test data integrity and access control
status: implementing
created: 2026-06-02
updated: 2026-06-02
archived_at: null
---

## Notes

Rollout Phase 2 of context/foundation/test-plan.md: "Data integrity and access control".

Risks covered: #3 (obligation data silently lost or corrupted after deploy), #4 (obligation ownership bypass — IDOR).

Test types planned: integration (real DB via Testcontainers).

Risk response intent:
- R3 — prove all obligation fields survive a Flyway-migrated schema without truncation, type coercion, or loss; prove update only changes requested fields and leaves all others intact.
- R4 — prove every endpoint that accepts an obligation ID returns 404 when the authenticated user does not own it; prove list endpoint never leaks cross-user data.
