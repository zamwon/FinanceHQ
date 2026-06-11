---
id: portfolio-price-currency-routing
title: Fix price currency routing — USD→PLN conversion for Twelve Data assets
status: implementing
created: 2026-06-11
updated: 2026-06-11
---

## Summary

Twelve Data returns USD prices for equities and crypto. Currently `fetchTwelveDataPrices`
stores everything in `plnPrices`, so `current_price_pln` holds raw USD values for
non-GPW assets. `recomputeSharePercents` then mixes USD and PLN values, producing
meaningless portfolio weights.

Fix: include `USD/PLN` as a dedicated entry in the Twelve Data batch request, use the
returned rate to convert each USD price to PLN, store USD in `current_price_usd` and
computed PLN in `current_price_pln`.
