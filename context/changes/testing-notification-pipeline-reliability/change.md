---
change_id: testing-notification-pipeline-reliability
title: Phase 1 — Test notification pipeline reliability
status: impl_reviewed
created: 2026-06-01
updated: 2026-06-01
archived_at: null
---

## Notes

Rollout Phase 1 of context/foundation/test-plan.md: "Notification pipeline reliability". 

Risks covered: #1 (notification email fails to arrive), #2 (wrong notification date from timezone/business-day edge case), #6 (duplicate emails on retry after DB failure). 

Test types planned: unit + integration. 

Risk response intent: 
- R1 — prove an obligation due 1 biz day from now triggers the scheduler and email is sent; challenge that "scheduler ran" means "email arrived."
- R2 — prove business-day and month-end date computation is correct AND the scheduler uses it correctly; challenge timezone/clock divergence.
- R6 — prove deduplication prevents duplicate email when send succeeds but DB write fails; challenge that UNIQUE constraint alone prevents duplicates.
