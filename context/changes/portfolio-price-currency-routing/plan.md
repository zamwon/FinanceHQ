# Fix Price Currency Routing: USD→PLN via Twelve Data Rate

## Overview

Twelve Data returns prices in USD. Today `fetchTwelveDataPrices` stores every result in
`plnPrices`, so `current_price_pln` holds raw USD values for non-GPW assets and
`recomputeSharePercents` mixes USD and PLN in the same sum — portfolio weights are
meaningless. The fix adds `USD/PLN` as a sentinel entry in the same Twelve Data batch,
extracts the rate, stores the native USD price in `current_price_usd`, and computes
`current_price_pln = price × rate`. GPW assets via Yahoo Finance are unaffected.

## Current State Analysis

`fetchTwelveDataPrices` (PortfolioPriceService.java:113-161) builds a batch body keyed
by ticker and stores every result in the `plnPrices` map. The save loop
(PortfolioPriceService.java:~103-117) writes `plnPrices` → `currentPricePln` for all
assets. `current_price_usd` was only populated for crypto via the old CoinGecko path;
it is now always null for Twelve Data assets.

`recomputeSharePercents` (PortfolioPriceService.java:292-313) uses `getCurrentPricePln()`
as the basis for all weight calculations. It handles null correctly — assets with null PLN
are excluded from the percentage sum.

The entity (`PortfolioAsset.java:47-51`) and DB schema
(`V10__create_portfolio_assets_table.sql:12-13`) already have separate nullable
`current_price_usd DECIMAL(20,8)` and `current_price_pln DECIMAL(20,4)` columns.
No migration needed.

## Desired End State

After this plan:
- `current_price_usd` is populated for every Twelve Data asset (US stocks + crypto).
- `current_price_pln = current_price_usd × USD/PLN_rate` for every Twelve Data asset
  when the rate is available; otherwise `current_price_pln` is null.
- `current_price_pln` for GPW/Yahoo Finance assets is unchanged (correct PLN from source).
- `recomputeSharePercents` produces accurate PLN-denominated portfolio weights.
- All backend tests pass; manual refresh with AAPL + BTC + PKN/XWAR confirms all three
  columns are semantically correct.

### Key Discoveries

- `RoundingMode` is already imported in `PortfolioPriceService.java` (used by
  `recomputeSharePercents`) — no import changes needed for the multiply step.
- The sentinel key `_USD_PLN` starts with `_` which no valid equity/crypto ticker starts
  with — collision-safe.
- `parseTwelveDataPrice` already handles null and parse errors gracefully — reuse it to
  extract the rate.
- Frame identified one open risk: Twelve Data `/batch` accepting a forex pair alongside
  equity/crypto pairs is unverified. The Phase 1 manual gate explicitly confirms this.

## What We're NOT Doing

- No `current_price_usd` computation for GPW/XWAR stocks — Yahoo Finance returns PLN only;
  reversing to USD via the rate is a future enhancement, not part of this fix.
- No schema migration.
- No DTO or API contract changes — `currentPriceUsd` and `currentPricePln` fields are
  already in `PortfolioAssetResponse`.
- No change to the Yahoo Finance path.

## Implementation Approach

Add one sentinel entry (`_USD_PLN`) to the Twelve Data batch request body after the
per-asset loop. After the batch response is parsed and the `data` map is extracted, read
the rate from `data["_USD_PLN"]` before iterating over assets. In the per-asset loop:
always put the price in `usdPrices`; put the converted value in `plnPrices` only when the
rate is available. The save loop already handles both maps — no change there.

## Critical Implementation Details

**Rate extraction before asset loop**: extract `usdPlnRate` from `data.get("_USD_PLN")`
before the per-asset `for` loop. If null or error status, log a `WARN` and proceed —
the asset loop then skips `plnPrices.put` for all Twelve Data assets.

**Scale consistency**: `p.multiply(usdPlnRate).setScale(4, RoundingMode.HALF_UP)` —
scale 4 matches `current_price_pln DECIMAL(20,4)` in the schema.

**Remove coinGeckoIdMapper routing in usdPrices**: the old code only put crypto in
`usdPrices`; now ALL Twelve Data assets (stocks + crypto) route to `usdPrices`.

---

## Phase 1: Add USD/PLN sentinel, fix routing, update tests

### Overview

All changes in `PortfolioPriceService.java` and `PortfolioPriceServiceTest.java`.
Adds the `_USD_PLN` batch sentinel, rewires the price routing, updates five existing
tests to include the sentinel in mock responses and assert USD prices, and adds one
new "rate missing" test.

### Changes Required

#### 1. `fetchTwelveDataPrices` — batch body

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioPriceService.java`

**Intent**: After the per-asset loop that builds `batchBody`, append one sentinel entry
so the Twelve Data batch also fetches the USD/PLN exchange rate.

**Contract**: `batchBody.put("_USD_PLN", Map.of("url", "/price?symbol=USD/PLN&apikey=" + twelveDataApiKey))`
appended as the last entry; the key `_USD_PLN` must not be used as a ticker anywhere in
the loop above it.

---

#### 2. `fetchTwelveDataPrices` — rate extraction

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioPriceService.java`

**Intent**: After the `data` map is extracted from the batch response (and before the
per-asset loop), pull the USD/PLN rate so it is available during per-asset price
conversion. Log a warning and leave rate null if the entry is absent or errored.

**Contract**:
```java
BigDecimal usdPlnRate = null;
if (data.get("_USD_PLN") instanceof Map<?, ?> rateEntry
        && "success".equals(rateEntry.get("status"))
        && rateEntry.get("response") instanceof Map<?, ?> ratePriceObj) {
    usdPlnRate = parseTwelveDataPrice(ratePriceObj.get("price")).orElse(null);
}
if (usdPlnRate == null) {
    log.warn("USD/PLN rate unavailable in Twelve Data batch; current_price_pln will be null for non-GPW assets");
}
```

---

#### 3. `fetchTwelveDataPrices` — per-asset price routing

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioPriceService.java`

**Intent**: Replace the old routing (crypto → both maps, stocks → plnPrices only) with
the new routing: all Twelve Data assets → `usdPrices`; PLN computed and placed in
`plnPrices` only when the rate is available.

**Contract**: Replace the `parseTwelveDataPrice(...).ifPresent(...)` block in the
per-asset loop with:
```java
final BigDecimal rate = usdPlnRate;
parseTwelveDataPrice(priceObj.get("price")).ifPresent(p -> {
    usdPrices.put(ticker, p);
    if (rate != null) {
        plnPrices.put(ticker, p.multiply(rate).setScale(4, RoundingMode.HALF_UP));
    }
});
```
The `coinGeckoIdMapper.toId(ticker).isPresent()` check on usdPrices is removed — all
Twelve Data assets are USD-priced.

---

#### 4. Update existing tests — add `_USD_PLN` to mock responses

**File**: `src/test/java/com/example/finance_hq/portfolio/PortfolioPriceServiceTest.java`

**Intent**: Five existing tests that set up Twelve Data mock responses need the
`_USD_PLN` sentinel entry added so the service can extract the rate. Use rate `"4.00"`
(round number, easy to verify in assertions).

Affected tests: `securitiesOnly_callsTwelveDataOnly_notCoinGecko`,
`cryptoOnly_callsTwelveData`, `mixedPortfolio_callsTwelveDataOnly`,
`sharePercent_computedCorrectly_threeAssets`, `unknownTicker_otherAssetsStillUpdate`.

**Contract**: Each mock `withSuccess(...)` body gains a `_USD_PLN` entry:
```
"\"_USD_PLN\":{\"response\":{\"price\":\"4.00\"},\"status\":\"success\"}"
```
appended to the `data` object. Update assertions that currently check only
`currentPricePln` for Twelve Data assets to also assert `currentPriceUsd`, and update
PLN assertion values to reflect `price × 4.00` (e.g. AAPL 200 → PLN 800, BTC 65000 →
PLN 260000, etc.). Share percent tests: ratios are invariant under uniform FX
multiplication — no assertion change needed for percentages.

---

#### 5. New test — rate missing degrades gracefully

**File**: `src/test/java/com/example/finance_hq/portfolio/PortfolioPriceServiceTest.java`

**Intent**: Verify that when `_USD_PLN` returns a batch error, the service still populates
`current_price_usd` for the asset and leaves `current_price_pln` null — no wrong data
written.

**Contract**: New `@Test void twelveDataRateMissing_populatesUsdOnly()` with a mock
response where `_USD_PLN` has `"status":"error"`. Asset: AAPL. Assertions:
`currentPriceUsd` = 200.00, `currentPricePln` = null.

---

### Success Criteria

#### Automated Verification

- All backend tests pass: `./mvnw test -Dfrontend.skip=true`
- Checkstyle passes (enforced by hook on every edit)

#### Manual Verification

- POST `refresh-prices` with AAPL + BTC in the portfolio: response shows
  `currentPriceUsd ≠ currentPricePln` for both, and `currentPricePln` is approximately
  `currentPriceUsd × current USD/PLN rate`.
- Verify the `_USD_PLN` entry actually appears in the Twelve Data batch response log
  (or add a temporary DEBUG log if needed) — this confirms batch endpoint accepts forex.
- PKN/XWAR (Yahoo Finance) asset: `currentPriceUsd` is null, `currentPricePln` is the
  PLN market price — unchanged.
- Second refresh within 15 min: `"refreshed": false` — throttle unaffected.

**Implementation Note**: After all automated checks pass, pause for manual confirmation
before committing.

---

## Testing Strategy

### Unit / Integration Tests

- **Updated existing**: Five Twelve Data tests gain the `_USD_PLN` mock entry and USD assertions.
- **New**: `twelveDataRateMissing_populatesUsdOnly` — rate-absent degradation.
- **Unchanged**: `gpwStocks_callYahooFinance_notTwelveData`, `throttle_withinStaleThreshold_returnsFalse_noApiCalls`.

### Manual Testing Steps

1. Add AAPL, BTC, and PKN/XWAR assets to the portfolio.
2. Call POST `/api/portfolio/refresh-prices`.
3. Assert response JSON: `currentPriceUsd` is non-null for AAPL and BTC; `currentPricePln ≈ currentPriceUsd × ~4.x`; PKN/XWAR has null `currentPriceUsd` and correct PLN.
4. Confirm `currentValuePln` and `currentSharePercent` are non-null and sum to ~100%.

## References

- Frame brief: `context/changes/portfolio-price-currency-routing/frame.md`
- Service: `src/main/java/com/example/finance_hq/portfolio/PortfolioPriceService.java`
- Tests: `src/test/java/com/example/finance_hq/portfolio/PortfolioPriceServiceTest.java`
- Entity: `src/main/java/com/example/finance_hq/portfolio/PortfolioAsset.java:47-51`
- Schema: `src/main/resources/db/migration/V10__create_portfolio_assets_table.sql:12-13`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Add USD/PLN sentinel, fix routing, update tests

#### Automated

- [x] 1.1 All backend tests pass: `./mvnw test -Dfrontend.skip=true`
- [x] 1.2 Checkstyle passes (no unused imports)

#### Manual

- [x] 1.3 AAPL + BTC refresh: `currentPriceUsd` ≠ `currentPricePln`; PLN ≈ USD × live rate
- [x] 1.4 USD/PLN entry appears in Twelve Data batch response (forex accepted by batch endpoint)
- [x] 1.5 PKN/XWAR: `currentPriceUsd` null, `currentPricePln` correct PLN value
- [x] 1.6 Second refresh within 15 min returns `"refreshed": false`
