# Fix Price Currency Routing: USD→PLN via Twelve Data Rate — Plan Brief

> Full plan: `context/changes/portfolio-price-currency-routing/plan.md`
> Frame brief: `context/changes/portfolio-price-currency-routing/frame.md`

## What & Why

Twelve Data returns prices in USD. The service has been writing those USD values
directly into `current_price_pln`, so `recomputeSharePercents` mixes USD and PLN
amounts in the same portfolio-weight sum — producing meaningless percentages.
The fix routes USD prices to `current_price_usd` and computes true PLN values by
fetching a `USD/PLN` rate from the same Twelve Data batch.

## Starting Point

`fetchTwelveDataPrices` (`PortfolioPriceService.java:113-161`) currently puts every
Twelve Data result into the `plnPrices` map, regardless of currency. The entity and
DB schema already have separate `current_price_usd` and `current_price_pln` nullable
columns — no schema change needed.

## Desired End State

After the fix, a refresh with AAPL + BTC + PKN/XWAR in the portfolio returns:
- AAPL / BTC: `currentPriceUsd` = Twelve Data USD price; `currentPricePln` = USD × live rate
- PKN/XWAR: `currentPricePln` = Yahoo Finance PLN price (unchanged); `currentPriceUsd` = null
- `currentSharePercent` is accurate and sums to ~100% across all assets

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Where to get USD/PLN rate | Twelve Data batch sentinel `_USD_PLN` | Same API call, no extra credit; user confirmed batch-first approach | Plan |
| Fallback when rate missing | `current_price_pln` → null; `current_price_usd` still stored | No wrong data written; `recomputeSharePercents` handles null gracefully | Plan |
| Forex-in-batch risk handling | Verify at Phase 1 manual gate; adapt if rejected | Unverified on free tier — discover at testing, not rework | Frame / Plan |
| Schema migration | None needed | Both columns already exist (`V10__...sql:12-13`) | Frame |

## Scope

**In scope:**
- `PortfolioPriceService.java` — `fetchTwelveDataPrices` batch body, rate extraction, per-asset routing
- `PortfolioPriceServiceTest.java` — update 5 existing tests + add 1 "rate missing" test

**Out of scope:**
- `current_price_usd` for GPW/XWAR stocks (Yahoo Finance returns PLN only; USD derivation is a future enhancement)
- Schema migration, DTO changes, new endpoints
- Yahoo Finance path — unchanged

## Architecture / Approach

The Twelve Data batch body gains one sentinel entry: `"_USD_PLN" → "/price?symbol=USD/PLN"`.
After parsing the `data` map, the service extracts `usdPlnRate` before the per-asset loop.
In the loop: every asset's native price goes to `usdPrices`; PLN = `price × rate` goes
to `plnPrices` when the rate is available. The existing save loop and
`recomputeSharePercents` are unchanged.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Fix routing + tests | `current_price_usd` and `current_price_pln` semantically correct for all asset types; 7 tests pass | Twelve Data batch may reject forex alongside equity/crypto — discovered at manual gate |

**Prerequisites:** Active `TWELVEDATA_API_KEY` in `application-local.properties` for manual verification  
**Estimated effort:** ~1 focused session; single file change + test updates

## Open Risks & Assumptions

- Twelve Data free tier `/batch` accepting a forex pair (`USD/PLN`) alongside equity and crypto pairs is **unverified** — the manual gate includes an explicit check for this. If rejected, fallback is a separate GET for the rate (slightly more code, one extra API credit per refresh).
- Rate `_USD_PLN` is assumed to return a single price value in the same response format as equity prices — this is the Twelve Data standard `/price` shape.

## Success Criteria (Summary)

- `currentPriceUsd` ≠ `currentPricePln` for AAPL and BTC after a live refresh
- `currentSharePercent` values are non-null and sum to approximately 100% across a mixed portfolio
- PKN/XWAR `currentPricePln` is unchanged and accurate
