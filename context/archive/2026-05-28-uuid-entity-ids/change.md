---
change_id: uuid-entity-ids
title: Migrate entity IDs from BIGSERIAL/Long to UUID
status: archived
created: 2026-05-28
updated: 2026-06-09
archived_at: 2026-06-09T12:25:39Z
---

## Notes

Prerequisite for add-and-list-obligations Phase 2+. UUID migration lands first so obligation controller, service, and tests are UUID-aware from the start.

After this change completes, resume add-and-list-obligations at Phase 2 — and drop the `id: number` line from Phase 4's obligation.model.ts contract (id stays `string`, UUID is already a string).
