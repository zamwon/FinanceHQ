---
change_id: uuid-entity-ids
title: Migrate entity IDs from BIGSERIAL/Long to UUID
status: implementing
created: 2026-05-28
updated: 2026-05-28
archived_at: null
---

## Notes

Prerequisite for add-and-list-obligations Phase 2+. UUID migration lands first so obligation controller, service, and tests are UUID-aware from the start.

After this change completes, resume add-and-list-obligations at Phase 2 — and drop the `id: number` line from Phase 4's obligation.model.ts contract (id stays `string`, UUID is already a string).
