<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Fix Price Currency Routing

- **Plan**: context/changes/portfolio-price-currency-routing/plan.md
- **Scope**: Phase 1 of 1
- **Date**: 2026-06-11
- **Verdict**: APPROVED
- **Findings**: 0 critical  2 warnings  2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — log.info for rate fires on every refresh

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: PortfolioPriceService.java:154
- **Detail**: log.info("USD/PLN rate from Twelve Data batch: {}", usdPlnRate) fires on every non-stale refresh. The missing-rate warn at line 152 is correctly WARN; the success case does not meet the bar for INFO in production logs.
- **Fix**: Change log.info to log.debug on line 154.
- **Decision**: FIXED

### F2 — Exception catch logs getMessage() instead of exception

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: PortfolioPriceService.java:177 (+ Yahoo Finance catch at line 207)
- **Detail**: log.warn("Twelve Data price fetch failed: {}", e.getMessage()) discards the exception type and stack trace. For RestClient exceptions, e.getMessage() is often null or unhelpful. Pre-existing code.
- **Fix**: Pass e directly as second SLF4J argument: log.warn("...", e)
- **Decision**: FIXED

### F3 — USD price not explicitly scaled before storage

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: PortfolioPriceService.java:169
- **Detail**: usdPrices.put(ticker, p) stored the raw parsed BigDecimal (scale = whatever API returns). DB column is DECIMAL(20,8). PLN is explicitly .setScale(4, HALF_UP). No data loss but inconsistent.
- **Fix**: usdPrices.put(ticker, p.setScale(8, RoundingMode.HALF_UP))
- **Decision**: FIXED

### F4 — ETH currentPriceUsd not asserted in cryptoOnly test

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: PortfolioPriceServiceTest.java:~80
- **Detail**: cryptoOnly_callsTwelveData asserted BTC's USD/PLN but skipped ETH. ETH gets identical routing; a second-asset regression would be silent.
- **Fix**: Added ETH currentPriceUsd (3500.00) and currentPricePln (14000.0000) assertions.
- **Decision**: FIXED
