---
date: 2026-06-10T08:21:54+00:00
researcher: Blazej Karnecki
git_commit: 17183c249beadb0fec47f7a363349d142fefe9df
branch: master
repository: FinanceHQ
topic: "Portfolio asset tracker with manual entry and CSV import"
tags: [research, codebase, portfolio, csv-import, price-api, twelve-data, coingecko, apache-commons-csv]
status: complete
last_updated: 2026-06-10
last_updated_by: Blazej Karnecki
---

# Research: Portfolio Asset Tracker with Manual Entry and CSV Import

**Date**: 2026-06-10T08:21:54+00:00  
**Researcher**: Blazej Karnecki  
**Git Commit**: 17183c249beadb0fec47f7a363349d142fefe9df  
**Branch**: master  
**Repository**: FinanceHQ

## Research Question

Add a feature that allows the user to insert portfolio assets both manually and via CSV import. Fields: asset (ticker), current price, shares, avg buy price [PLN], avg buy price [asset currency], purchase value [PLN], purchase value [asset currency], share of portfolio %, group of asset. Current price should be pulled from a free stock data API at least every 24h. If free tier allows, increase frequency.  
Coverage required: stocks (US + Polish/EU/GPW), ETFs, and crypto.

---

## Summary

The feature maps cleanly onto the existing domain pattern established by `expense-income-tracking`. A new `portfolio/` package follows the entity → repository → service → controller → DTO convention. The next Flyway migration is **V10** (`V9__add_last_paid_date_to_obligations.sql` is the current head). Price fetching should use a **two-API approach**: Twelve Data for stocks + ETFs (including GPW Warsaw with the `XWAR` exchange qualifier) and CoinGecko Demo for crypto (with native PLN conversion). CSV import uses **Apache Commons CSV 1.12.0** — zero transitive dependencies, header-flexible, and integrates cleanly with the existing `ProblemDetail` error pattern. The scheduler architecture mirrors the notification scheduler (`@Scheduled` + `@Transactional`).

---

## Detailed Findings

### 1. Codebase Patterns to Follow

The `expense-income-tracking` feature (phases 1–6, completed 2026-06-10) is the canonical blueprint. Every new domain follows:

**Entity** — `src/main/java/com/example/finance_hq/<domain>/<Domain>.java`
- UUID PK via `@GeneratedValue(strategy = GenerationType.UUID)`
- `@ManyToOne(fetch = FetchType.LAZY)` reference to `User` (non-nullable)
- `createdAt` set via `@PrePersist`
- Financial amounts as `BigDecimal` with `@Column(precision = 15, scale = 2)`
- Enums for type/category fields; nullable fields for optional data

**Repository** — `<Domain>Repository.java`
- Disable `findById(UUID)` with `UnsupportedOperationException` (security — prevents cross-user ID guessing)
- All lookups scoped to user: `findByIdAndUser(UUID id, User user)`
- Paginated list: `findAllByUser(User, Pageable)` with `PageRequest.of(0, 200, Sort.by(DESC, "createdAt"))`
- Scheduler queries use `JOIN FETCH` to avoid N+1

**Service** — `<Domain>Service.java`
- `@Transactional(readOnly = true)` for queries, default `@Transactional` for mutations
- Business validation in service layer (not just Bean Validation)
- Custom runtime exceptions: `<Domain>NotFoundException`, `Invalid<Domain>Exception`
- Public methods above private helpers (lessons.md rule)

**Controller** — `<Domain>Controller.java`
- `@RestController` + `@RequestMapping("/api/<domain>")`
- `@AuthenticationPrincipal User user` on all protected methods
- `@Valid` on all request bodies
- Status codes: 201 for creates, 200 for updates/reads, 204 for deletes

**DTOs** — `dto/` subpackage, all Java records
- `Create<Domain>Request`: `@NotNull`/`@NotBlank` + Jakarta validation
- `Update<Domain>Request`: same fields but all nullable (partial update)
- `<Domain>Response`: all entity fields + computed fields; static `from(Entity)` factory
- `@Digits(integer = 15, fraction = 2)` + `@DecimalMin("0.01")` for amounts

**Exceptions** — declared at package level, registered in `GlobalExceptionHandler` (in `auth/exception/`) returning RFC 7807 `ProblemDetail`

**Frontend** — `src/main/frontend/src/app/features/<domain>/`
- `<domain>.service.ts`: `BASE = '/api/<domain>'`; HttpClient methods returning `Observable`
- `<domain>.model.ts`: TypeScript interfaces matching Java response records
- `<domain>.component.ts`: Angular signals for state (`signal<T[]>`, `signal<boolean>`, `signal<T | null>`)
- Dialog subcomponents: `<domain>-dialog/` (add/edit), `delete-dialog/`
- Route added to `app.routes.ts` under the protected layout

**Security** — new SPA route added to `permitAll` in `SecurityConfig.java`; all `/api/<domain>/**` endpoints fall under `anyRequest().authenticated()` (already covered by default)

**HTTP test file** — `src/test/http-test-requests/<domain>.http` (lessons.md rule)

---

### 2. Database Migration

**Next migration: V10**  
Current migration head: `V9__add_last_paid_date_to_obligations.sql`

The `portfolio_assets` table will need:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `ticker VARCHAR(20) NOT NULL` — asset symbol (e.g. `AAPL`, `PKN`, `BTC`)
- `asset_name VARCHAR(255)` — human-readable name (optional, fetched from API)
- `asset_group VARCHAR(50) NOT NULL` — user-defined group (e.g. `US_STOCKS`, `CRYPTO`, `ETF`)
- `shares DECIMAL(20, 8) NOT NULL` — higher precision than financials (crypto needs 8 decimal places)
- `avg_buy_price_pln DECIMAL(20, 4) NOT NULL`
- `avg_buy_price_asset_currency DECIMAL(20, 8) NOT NULL`
- `purchase_value_pln DECIMAL(20, 4) NOT NULL`
- `purchase_value_asset_currency DECIMAL(20, 8) NOT NULL`
- `current_price_usd DECIMAL(20, 8)` — nullable (null until first price fetch)
- `current_price_pln DECIMAL(20, 4)` — nullable
- `price_last_updated_at TIMESTAMP WITH TIME ZONE` — nullable
- `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`
- Index on `user_id`

**Price fetch table (optional, V11)**: `price_fetch_log` — tracks fetch attempts, errors, timestamps per ticker per source. Mirrors the notification_log pattern.

---

### 3. Price Data API Strategy

**Recommendation: Two-API approach**

#### Primary: Twelve Data (stocks + ETFs including GPW/Warsaw)

- **Free tier**: 800 req/day, 8 req/min
- **Coverage**: US equities, Warsaw Stock Exchange (`XWAR` exchange), EU stocks, ETFs, 750+ crypto
- **Polish tickers**: `PKN` on exchange `XWAR`, `CDR` on exchange `XWAR` (format: `symbol=PKN&exchange=XWAR`)
- **Batch endpoint**: `GET /price?symbol=AAPL,PKN/XWAR,CDR/XWAR&apikey={key}` — one call fetches N symbols
- **Auth**: API key as query param (`apikey=YOUR_KEY`) — free account registration required
- **Rate limit**: HTTP 429 response (clean, not a 200 with error body like Alpha Vantage)
- **Integration**: `RestClient` + Jackson; `/price` endpoint returns simple `{"price": "200.99"}` per symbol

```java
// application.properties — follow fallback-default pattern (lessons.md)
twelvedata.api.key=${TWELVEDATA_API_KEY:demo}
twelvedata.base.url=https://api.twelvedata.com
```

**Verify GPW free-tier data freshness** before finalizing: call `/quote?symbol=PKN&exchange=XWAR` and check `is_market_open` + `datetime` fields.

#### Secondary: CoinGecko Demo (crypto)

- **Free tier**: 10,000 calls/month (~333/day) with free account registration; Demo API key
- **Coverage**: 17,000+ coins, 38M+ tokens — comprehensive crypto universe
- **Native PLN**: `vs_currencies=usd,pln` in the same call eliminates a separate FX API
- **Auth**: `x-cg-demo-api-key: YOUR_KEY` header
- **Rate limit**: HTTP 429 with `Retry-After` header
- **Gotcha**: uses internal IDs (`bitcoin`, `ethereum`), not ticker symbols (`BTC`, `ETH`) — requires one-time ID mapping fetch via `/coins/list`

```java
// One call fetches prices for multiple coins in USD and PLN simultaneously
GET /api/v3/simple/price?ids=bitcoin,ethereum,solana&vs_currencies=usd,pln
```

#### Skip (not recommended)

| API | Reason to skip |
|---|---|
| Yahoo Finance | No stable endpoint contract; cookie auth breaks silently in prod |
| Alpha Vantage | 25 req/day free — blocks any portfolio > 5 assets |
| Polygon.io | No EU/Polish stock coverage on free tier |
| Stooq | CSV-only output, undocumented API, no crypto |

#### Optional: ExchangeRate-API (PLN FX only)

If direct PLN prices are unavailable for some securities, `open.er-api.com` (no auth) provides daily USD/PLN rates. Cache with Spring `@Cacheable` for 24h. Only needed if Twelve Data doesn't return PLN prices for Warsaw-listed stocks.

---

### 4. Price Scheduler Architecture

Mirror the existing `NotificationScheduler` pattern:

- `PortfolioPriceScheduler` — `@Component` with `@Scheduled(cron = "0 0 8 * * *")` (8 AM daily, Warsaw time)
- Fetches all distinct tickers for all users (single batch call per API)
- Updates `current_price_usd`, `current_price_pln`, `price_last_updated_at` on each asset row
- On API failure: log the error, leave existing price in place (don't null out)
- **Bounded retry**: unlike `NotificationScheduler` (which retries forever — a known tech debt), add a `price_fetch_error_count` column and skip assets with > 5 consecutive failures, logging a WARNING
- The `@Scheduled` frequency can be increased if API free tier allows (Twelve Data: 800/day free, CoinGecko: 333/day free)

---

### 5. CSV Import Architecture

**Library: Apache Commons CSV 1.12.0**

Selected over OpenCSV (rigid schema, poor optional-column handling) and Jackson CSV (verbose schema configuration) because:
- Zero transitive dependencies
- `setIgnoreHeaderCase(true)` handles user-provided column name variations
- `isMapped()` check gracefully handles optional columns (`current_price`)
- Row-level error aggregation loops integrate naturally with existing `ProblemDetail` error pattern
- European decimal comma tolerance (`"1.234,56"` → replace `,` with `.`)

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-csv</artifactId>
    <version>1.12.0</version>
</dependency>
```

Add to `application.properties`:
```properties
spring.servlet.multipart.max-file-size=5MB
spring.servlet.multipart.max-request-size=5MB
```

**Endpoint**: `POST /api/portfolio/import` with `consumes = MULTIPART_FORM_DATA_VALUE`

**Response pattern**:
- All rows valid → `200 OK` with `{"importedCount": N}`
- Any row error → `422 Unprocessable Entity` (ProblemDetail) with `rowErrors: [{rowNumber, column, message}]` — fail-fast (don't partial-import)
- Bad file type → `400 Bad Request`
- File too large → `413 Payload Too Large` (handler in `GlobalExceptionHandler` for `MaxUploadSizeExceededException`)
- Missing required headers → `400 Bad Request` (hard stop before row iteration)

**Required CSV columns** (case-insensitive): `asset`, `shares`, `avg_buy_price_pln`, `avg_buy_price_asset_currency`, `purchase_value_pln`, `purchase_value_asset_currency`, `portfolio_share_percent`, `asset_group`  
**Optional column**: `current_price` (fetched from API if absent)

**Row count cap**: 10,000 rows max (guard against heap exhaustion from multi-MB CSVs).

**Security notes**:
- Content-type check is a first gate only (client-controlled header, easily spoofed)
- `MultipartFile.getInputStream()` is in-memory — no path traversal risk
- Always decode with `StandardCharsets.UTF_8` explicitly

---

### 6. Frontend Design

The portfolio page follows the same Angular signal + dialog pattern as `TransactionsComponent`:

- **Route**: `/portfolio` added to `app.routes.ts` under protected layout
- **Sidebar**: new nav item in `sidebar.component.html`
- **Service**: `portfolio.service.ts` with `BASE = '/api/portfolio'`; methods: `getAll()`, `create()`, `update()`, `delete()`, `importCsv(file: File)`
- **Model**: `portfolio-asset.model.ts` — `PortfolioAsset` interface with all response fields + computed `currentValue`
- **Table**: display columns: ticker, group, shares, avg buy (PLN), current price, purchase value (PLN), portfolio %, last price updated
- **Import button**: file input `<input type="file" accept=".csv">` triggering `importCsv()` — returns row errors for display in a toast or inline error list
- **Price freshness indicator**: show `price_last_updated_at` age next to current price; highlight stale (> 25h old) in amber

---

## Code References

### Existing patterns to mirror
- `src/main/java/com/example/finance_hq/transaction/Transaction.java:13-108` — entity pattern (UUID PK, user FK, BigDecimal fields, @PrePersist)
- `src/main/java/com/example/finance_hq/transaction/TransactionRepository.java:11-22` — disabled findById, user-scoped queries
- `src/main/java/com/example/finance_hq/transaction/TransactionService.java:21-123` — service pattern (@Transactional(readOnly=true), validation, pagination)
- `src/main/java/com/example/finance_hq/transaction/TransactionController.java:15-52` — controller pattern (status codes, @Valid, @AuthenticationPrincipal)
- `src/main/java/com/example/finance_hq/notification/NotificationScheduler.java` — scheduler pattern to mirror for price updates
- `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java` — exception handler where `MaxUploadSizeExceededException` and `InvalidCsvException` handlers must be added
- `src/main/java/com/example/finance_hq/security/SecurityConfig.java:26-97` — add `/portfolio` SPA route to permitAll

### New files to create
- `src/main/resources/db/migration/V10__create_portfolio_assets_table.sql`
- `src/main/java/com/example/finance_hq/portfolio/PortfolioAsset.java` (entity)
- `src/main/java/com/example/finance_hq/portfolio/PortfolioAssetRepository.java`
- `src/main/java/com/example/finance_hq/portfolio/PortfolioAssetService.java`
- `src/main/java/com/example/finance_hq/portfolio/PortfolioAssetController.java`
- `src/main/java/com/example/finance_hq/portfolio/PortfolioPriceScheduler.java`
- `src/main/java/com/example/finance_hq/portfolio/PriceProviderService.java` (wraps TwelveData + CoinGecko)
- `src/main/java/com/example/finance_hq/portfolio/PortfolioCsvImportService.java`
- `src/main/java/com/example/finance_hq/portfolio/dto/` (CreatePortfolioAssetRequest, UpdatePortfolioAssetRequest, PortfolioAssetResponse, CsvImportResult, RowError)
- `src/test/http-test-requests/portfolio.http`
- `src/main/frontend/src/app/features/portfolio/` (component + service + model + dialogs)

---

## Architecture Insights

1. **Package boundary**: Keep `portfolio/` self-contained. Do not create FKs from `PortfolioAsset` to `Transaction` — they are separate bounded contexts. A transaction can optionally reference a portfolio asset (for sell tracking), but that's a future concern.

2. **Decimal precision**: `shares` and prices need higher precision than the existing `DECIMAL(15,2)` used for obligation amounts. Crypto shares (e.g. `0.00523148 BTC`) require 8 decimal places; crypto prices can exceed 5 significant figures. Use `DECIMAL(20, 8)` for shares and crypto prices, `DECIMAL(20, 4)` for PLN-denominated values.

3. **Price storage strategy**: Store `current_price_usd` and `current_price_pln` separately on the entity row — don't recompute on every API call. This ensures the UI shows the last known price even if the scheduler fails.

4. **CoinGecko ID mapping**: The CoinGecko `ids` field (e.g. `bitcoin`) differs from exchange tickers (`BTC`). Maintain a small lookup table (either hardcoded enum or V11 migration for `coin_gecko_ids` table) that maps common crypto tickers to CoinGecko IDs. For MVP, a hardcoded map of ~50 common coins is sufficient.

5. **Portfolio share %**: Can be computed server-side from `purchase_value_pln` / `SUM(purchase_value_pln)` on the fly in `PortfolioAssetResponse`, rather than storing it. Or store it from the import and recompute on CRUD. Decision needed at plan time.

6. **Scheduler idempotency**: The price update scheduler should be idempotent — re-running it never creates duplicates, only updates `current_price` + `price_last_updated_at`. Different from the notification scheduler (which creates NotificationLog records).

---

## Historical Context (from prior changes)

- `context/changes/expense-income-tracking/plan.md` — Six-phase implementation that is the direct blueprint for this feature. Phase 1 (DB migration), Phase 2 (backend CRUD), Phase 4 (frontend), Phases 5–6 (analytics) are all relevant. The scheduler guard pattern (Phase 3) informs price scheduler design.
- `context/changes/expense-income-tracking/reviews/impl-review-phase-2.md` — F3/F4 findings: always add 401 auth-boundary tests and cross-user read-isolation tests. Apply same to portfolio integration tests.
- `context/changes/expense-income-tracking/reviews/impl-review-phase-3.md` — F1: boundary date bugs (`isBefore` vs `!isAfter`). F2: inline path literals. Apply both lessons.
- `context/changes/expense-income-tracking/reviews/impl-review-phase-5.md` — F1: YEAR/MONTH JPQL extensions are non-standard; use date-range predicates. Relevant if portfolio adds analytics queries.
- `context/archive/2026-05-28-uuid-entity-ids/` — Migrated all PKs to UUID. New entities must use UUID from the start (already standard).
- `context/archive/2026-06-09-resend-email-integration/` — Email via Resend is already wired. Not directly relevant to portfolio, but the pattern of environment-variable-backed API clients (API key in properties, injected by Railway) applies to Twelve Data and CoinGecko keys.
- `context/foundation/lessons.md` — All 16 lessons apply. Most critical for this feature:
  - Lesson 1 (Flyway Spring Boot 4.x module): `spring-boot-flyway` already in pom.xml ✓
  - Lesson 3 (Testcontainers 2.x names): already correct in pom.xml ✓
  - Lesson 5 (env var fallback defaults): apply to `TWELVEDATA_API_KEY` and `COINGECKO_API_KEY`
  - Lesson 15 (@Transactional on @SpringBootTest): all integration tests must have this
  - Lesson 16 (.http test files): create `portfolio.http` and `portfolio-import.http`

---

## Related Research

- No prior `research.md` exists for related changes in `context/changes/` or `context/archive/`.

---

## Open Questions

1. **Portfolio share % storage**: Compute on-the-fly in response (from sum of purchase values) or store in DB and update on CRUD? On-the-fly is simpler and always consistent; stored is faster for large portfolios. Given single-user personal tracker, on-the-fly is fine.

2. **CoinGecko ID mapping**: Hardcoded map for ~50 common tickers, or V11 migration with a `crypto_tickers` lookup table? Hardcoded map is sufficient for MVP.

3. **Multiple lots**: Can the same ticker appear multiple times (bought at different prices on different dates)? The change notes don't specify. If yes, the table needs a `lot_id` or the display should show per-lot rows and a summary row. Needs clarification before plan.

4. **Sell tracking**: Out of scope for this change? If sells are not tracked, portfolio value will diverge from reality when positions are closed. Worth noting as a limitation even if not implemented.

5. **Price update frequency**: 800 req/day (Twelve Data) ÷ typical portfolio size (10–30 tickers) = 26–80 updates/day possible. Could refresh every hour during market hours (9 AM–5 PM Warsaw time) rather than once at 8 AM. Worth designing the scheduler with configurable cron.

6. **GPW data freshness**: Twelve Data free tier may deliver end-of-day (not real-time) for Warsaw-listed stocks. Verify by testing `/quote?symbol=PKN&exchange=XWAR` during GPW market hours before committing to this API choice for Polish equities.

7. **Error handling on partial price fetch**: If Twelve Data returns prices for 8 of 10 tickers (2 unknown symbols), should the scheduler update the 8 successfully and log the 2 failures, or abort all? Partial update is more user-friendly.
