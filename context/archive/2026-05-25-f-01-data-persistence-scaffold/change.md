---
change_id: f-01-data-persistence-scaffold
title: Set up PostgreSQL connectivity and schema migration tooling
status: archived
created: 2026-05-25
updated: 2026-05-25
archived_at: 2026-05-25T15:16:35Z
---

## Notes

F-01 from roadmap. Wire PostgreSQL connectivity and Flyway (or Liquibase) schema migration; seed the base user table. Apply Supabase Session Pooler URL per context/foundation/infrastructure.md before first deploy. No H2 — PostgreSQL in all environments. Unlocks F-02 (auth) and S-02 (obligation storage).
