# Portfolio Asset Tracker with Manual Entry and CSV Import — Plan Brief

> Full plan: `context/changes/portfolio-asset-agregator-with-csv-import/plan.md`
> Research: `context/changes/portfolio-asset-agregator-with-csv-import/research.md`

## What & Why

Add a portfolio asset tracking page where the user records investment positions (one per ticker), imports from a pre-aggregated CSV, and sees live market prices. The goal is to centralize portfolio tracking alongside obligation tracking in one tool — with auto-refreshed prices eliminating the need for a separate spreadsheet.

## Starting Point

Five domains already implemented (auth, obligations, transactions, dashboard, notifications) following a consistent entity → repository → service → controller → Angular signal pattern. The `expense-income-tracking` feature (phases 1–6, complete 2026-06-10) is the direct architectural blueprint. V9 is the current Flyway migration head.

## Desired End State

The user navigates to `/portfolio`, sees their positions in a table with live PLN prices, and can add/edit/delete positions manually or bulk-import from a CSV. After login, prices auto-refresh from Twelve Data (stocks/ETFs including Warsaw GPW) and CoinGecko (crypto) — throttled to once every 15 minutes. Both purchase share % and current-value share % are visible. A stale-price indicator appears when prices are > 25 hours old.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Multiple lots per ticker | No — one position per ticker | CSV data is pre-aggregated; import is a replace-per-ticker operation | Plan |
| Portfolio share % | Stored from import; current % recomputed on price refresh | Shows both purchase allocation and current market allocation | Plan |
| Price refresh trigger | Frontend `POST /api/portfolio/refresh-prices` on page load; 15-min server throttle | Decouples auth flow from external API latency; backend is the authority on staleness | Plan |
| CSV conflict behavior | Full UPSERT per ticker (idempotent re-imports) | Re-importing same CSV must produce same state | Plan |
| Manual create on dup ticker | 409 Conflict (not UPSERT) | Manual creation should inform user; only CSV import is idempotent | Plan |
| Asset group | Free-text VARCHAR(100) | User-defined grouping (Tech, Dividend, GPW) without code changes | Plan |
| Price API — securities | Twelve Data (800 req/day free, XWAR for GPW) | Only free API with confirmed Warsaw Stock Exchange coverage | Research |
| Price API — crypto | CoinGecko Demo (10,000 calls/month, native PLN) | Best crypto coverage; PLN in same request eliminates separate FX API | Research |
| CSV library | Apache Commons CSV 1.12.0 | Zero transitives; header-flexible; maps to existing ProblemDetail error pattern | Research |
| Price routing | CoinGecko map lookup first; else Twelve Data | Strip `/EXCHANGE` suffix before lookup; no extra DB column | Plan |

## Scope

**In scope:**
- Portfolio CRUD: add/edit/delete one position per ticker
- CSV import: UPSERT per ticker, all-or-nothing on row errors, 10,000-row cap
- Price refresh: Twelve Data (stocks/ETFs/GPW) + CoinGecko (crypto), 15-min throttle
- `current_share_percent` recomputed atomically on each price refresh
- Angular portfolio page: table, dialogs, import button, stale-price indicator

**Out of scope:**
- Multiple lots / purchase history
- Sell tracking / position closing
- Historical price storage or trend charts
- Scheduled background price fetch
- AI or investment recommendations

## Architecture / Approach

New self-contained `portfolio/` backend package mirrors the `transaction/` domain. `PortfolioPriceService` partitions assets by CoinGecko map membership (crypto vs securities) and calls both APIs in one refresh cycle. Throttle check uses `MAX(price_last_updated_at)` from the table — no extra metadata table. `POST /api/portfolio/refresh-prices` returns the full portfolio list, eliminating a separate GET after navigation. CSV import uses Apache Commons CSV with header-flexible config; UPSERTs on (user_id, ticker).

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. DB Migration (V10) | `portfolio_assets` table, UNIQUE(user_id, ticker), precision columns | None — pure additive migration |
| 2. Backend CRUD | Entity/repo/service/controller, CRUD endpoints, tests | 409 duplicate-ticker logic must differ from UPSERT in CSV |
| 3. Price Refresh | Twelve Data + CoinGecko clients, refresh endpoint, 15-min throttle | Twelve Data GPW free-tier data freshness unverified until tested live |
| 4. CSV Import | Apache Commons CSV, UPSERT import, row-error 422 responses | European decimal comma handling; silent ignore of `current_price` column |
| 5. Frontend | Portfolio table, dialogs, import button, post-load price refresh | Stale-price indicator UX; no double-fetch within 15 min |

**Prerequisites:** Active Twelve Data API key and CoinGecko Demo API key for Phase 3 manual verification.  
**Estimated effort:** ~5 sessions across 5 phases (similar scale to expense-income-tracking phases 1–4).

## Open Risks & Assumptions

- Twelve Data free tier may return end-of-day (not real-time) prices for Warsaw GPW — needs live verification during market hours in Phase 3; if confirmed stale, document as a known limitation
- CoinGecko hardcoded ID map covers ~21 common coins — exotic altcoins will not price (WARN logged, no failure)
- Single-symbol vs multi-symbol Twelve Data `/price` response shapes differ — implementation must handle both

## Success Criteria (Summary)

- User can manage positions (manual entry) and import from CSV; re-importing is idempotent
- After navigating to the portfolio page, prices refresh from Twelve Data + CoinGecko; subsequent visits within 15 min use cached prices
- `currentSharePercent` reflects live market weight, not just purchase weight
