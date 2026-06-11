# Frame Brief: Fix price currency routing — USD→PLN conversion for Twelve Data assets

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed.

## Reported Observation

For all non-GPW assets fetched via Twelve Data (US equities and crypto), USD prices
are stored in `current_price_pln` instead of `current_price_usd`. `current_price_pln`
holds raw USD values. `recomputeSharePercents` then sums `shares × current_price_pln`
across all assets, mixing AAPL at $200 and PKN at 145 PLN as if they were the same
currency — portfolio weights are meaningless.

## Initial Framing (preserved)

- **User's stated cause**: `fetchTwelveDataPrices` populates `plnPrices` for all assets regardless of the actual currency Twelve Data returns; the save loop stores that blindly into `current_price_pln`.
- **User's proposed direction**: Include `USD/PLN` as an extra entry in the Twelve Data batch; multiply USD prices by the rate; store Twelve Data native price in `current_price_usd`; store converted value in `current_price_pln`.
- **Pre-dispatch narrowing**: Both symptoms equally important — column semantics AND share % computation must be correct. Twelve Data free tier has USD/PLN (URL evidence provided, not batch-endpoint-verified).

## Dimension Map

The observation could originate at any of these dimensions:

1. **Data model** — entity/DB schema lacks a `current_price_usd` column; no place to store the USD price.
2. **Price fetch routing** — `fetchTwelveDataPrices` writes USD prices into `plnPrices`; the save loop stores them in `current_price_pln`. ← **initial framing**
3. **Currency conversion gap** — no USD→PLN rate is fetched; `plnPrices` can never hold a real PLN value for Twelve Data assets.
4. **Share % computation** — `recomputeSharePercents` mixes currencies because it receives wrong input from dimension 2/3.

## Hypothesis Investigation

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| Data model missing column | `PortfolioAsset.java:47-51`; `V10__create_portfolio_assets_table.sql:12-13` — `current_price_usd DECIMAL(20,8)` and `current_price_pln DECIMAL(20,4)` both present, nullable, correct semantics | **NONE** — schema is correct, no change needed |
| Price fetch routing (initial framing) | `PortfolioPriceService.java:155` — `plnPrices.put(ticker, p)` for all Twelve Data results; no currency discrimination; USD price lands in `current_price_pln` at save loop line ~106-113 | **STRONG** — direct code path confirmed |
| Currency conversion gap | `PortfolioPriceService.java:113-161` — no FX rate fetch anywhere in the service; `plnPrices` is treated as "already PLN" | **STRONG** — confirmed by absence |
| Share % computation is the root | `PortfolioPriceService.java:292-313` — `recomputeSharePercents` is a pure consumer of `current_price_pln`; handles null correctly; would produce correct results if PLN input were correct | **NONE** — computation is correct; wrong input is the cause |

## Narrowing Signals

- Both column semantics and share % computation must be fixed — they are the same fix (correct the PLN value and both symptoms resolve simultaneously).
- Twelve Data free tier includes USD/PLN forex; URL evidence: `twelvedata.com/markets/105891/forex/usd-pln/historical-data`. Batch endpoint support for forex alongside equities is **unverified** — this is the one risk to surface in the plan.

## Cross-System Convention

Financial data APIs typically return prices in native currency (USD for US equities, USD for BTC/USD, PLN for Warsaw equities). The correct pattern is: fetch native price → store in `current_price_usd` → convert to reporting currency (PLN) via FX rate → store in `current_price_pln`. This system already follows that pattern for Yahoo Finance (returns PLN directly). The fix makes Twelve Data consistent.

## Reframed (or Confirmed) Problem Statement

> **The initial framing was correct.** The actual problem to plan around is: `fetchTwelveDataPrices` misroutes USD prices to `plnPrices`; there is no FX conversion step; `current_price_pln` for non-GPW assets holds raw USD. The fix is to (1) include `USD/PLN` in the Twelve Data batch, (2) route Twelve Data prices to `current_price_usd`, (3) compute `current_price_pln = current_price_usd × rate`, and (4) degrade gracefully (leave PLN null) if the rate entry is missing.

## Confidence

**HIGH** — strong evidence at dimensions 2 and 3, confirmed by direct code reads; schema already supports the fix; Yahoo Finance path is already the model to follow.

Risk to surface in plan: Twelve Data `/batch` endpoint acceptance of forex pairs alongside equity/crypto is not batch-tested. Plan should include a manual verification step for `USD/PLN` in a real batch call.

## What Changes for /10x-plan

The plan should be scoped to `PortfolioPriceService.java` only — no schema migration, no DTO changes, no new config. Changes: add `USD/PLN` to the batch request as a sentinel key; after parsing, extract the rate; for each non-GPW asset compute PLN from the rate; store USD in `usdPrices` and PLN in `plnPrices`. Update tests to assert `current_price_usd` ≠ `current_price_pln` for a US stock or crypto asset.

## References

- `src/main/java/com/example/finance_hq/portfolio/PortfolioPriceService.java` (lines 113-161, 292-313)
- `src/main/java/com/example/finance_hq/portfolio/PortfolioAsset.java` (lines 47-54)
- `src/main/resources/db/migration/V10__create_portfolio_assets_table.sql` (lines 12-13)
