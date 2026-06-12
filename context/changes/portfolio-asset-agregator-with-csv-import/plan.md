# Portfolio Asset Tracker with Manual Entry and CSV Import — Implementation Plan

## Overview

New self-contained `portfolio/` domain enabling the user to track investment positions (one per ticker), import via CSV, and auto-refresh prices from Twelve Data (stocks/ETFs/Warsaw GPW) and CoinGecko (crypto). Price refresh is triggered by the frontend after navigating to the portfolio page, throttled server-side to at most once per 15 minutes.

## Current State Analysis

**Existing patterns**: Five domains in production (auth, obligations, transactions, dashboard, notifications) follow a consistent entity → repository → service → controller → Angular convention. `expense-income-tracking` (phases 1–6, complete 2026-06-10) is the direct blueprint.

**Current migration head**: `V9__add_last_paid_date_to_obligations.sql` — next migration is V10.

**Angular signals pattern**: All feature components use `signal<T>` (Angular 17+) for reactive state; services are stateless HTTP wrappers.

**Error handling**: All API errors return RFC 7807 `ProblemDetail`; `GlobalExceptionHandler` is in `auth/exception/`.

**Security scoping**: `findById(UUID)` is disabled on all repositories; all lookups scoped via `findByIdAndUser(UUID, User)`.

## Desired End State

The user navigates to `/portfolio`, sees their investment positions in a table with live PLN prices, can add/edit/delete positions manually, import from a pre-aggregated CSV, and see both purchase share % and current-value share %. Prices refresh automatically on each visit (with a 15-min server-side cooldown). A stale-price indicator appears when prices are > 25 hours old.

### Key Discoveries:

- `Transaction.java:13-108` — entity pattern (UUID PK, BigDecimal `precision=15, scale=2`, `@PrePersist` for `createdAt`, LAZY FK to User)
- `TransactionRepository.java:11-22` — security scoping: `findById` throws `UnsupportedOperationException`
- `TransactionService.java:21-123` — `@Transactional(readOnly=true)` for reads, default `@Transactional` for mutations; private methods below public (lessons.md)
- `TransactionController.java:15-52` — `@AuthenticationPrincipal User user`, `@Valid` on bodies, 201/200/204 status codes
- `GlobalExceptionHandler.java` — `auth/exception/` package; RFC 7807 `ProblemDetail` returns
- `SecurityConfig.java:26-97` — SPA routes in `permitAll`; `anyRequest().authenticated()` covers all `/api/**`
- `application.properties` — `${VAR:default}` pattern for all env vars (lessons.md rule)
- Lessons: Testcontainers 2.x artifact names; `@Transactional` on `@SpringBootTest`; `.http` file required per endpoint group; private methods below public; extract path constants

## What We're NOT Doing

- Multiple lots per ticker (one position per ticker; CSV is pre-aggregated)
- Scheduled background price fetch (`@Scheduled` cron — user-triggered instead)
- Sell tracking / position closing
- Historical price storage or trend charts
- AI/investment recommendations
- Bank or brokerage integrations

## Implementation Approach

Five sequential phases: DB migration → backend CRUD → price refresh service → CSV import → Angular frontend. Each phase has a manual verification gate before the next begins.

**Price routing**: `CoinGeckoIdMapper` holds a hardcoded `Map<String, String>` (~21 coins). For each asset, strip the `/EXCHANGE` suffix from the ticker, look it up in the map — if found, route to CoinGecko; otherwise route to Twelve Data (passing the full ticker-with-exchange string). No extra DB column required.

**CSV import semantics**: UPSERT on (user_id, ticker). Re-importing the same CSV is fully idempotent. Manual `POST /api/portfolio` returns `409 Conflict` on duplicate ticker (not UPSERT) — manual creation should inform the user that a position already exists.

**Price throttle**: `PortfolioPriceService` checks `MAX(price_last_updated_at)` from the user's assets against a configurable stale threshold (default 15 min). No extra metadata table needed.

## Critical Implementation Details

**UPSERT vs 409**: CSV import uses UPSERT (find by user + ticker, update if found). Manual `POST /api/portfolio` throws `InvalidPortfolioAssetException` if the ticker already exists for the user — message `"Position for {ticker} already exists. Use PATCH to update."`. This difference is intentional.

**Exchange-qualified tickers**: Tickers for non-US exchanges should be entered as `TICKER/EXCHANGE` (e.g., `PKN/XWAR`). The `CoinGeckoIdMapper` strips everything after `/` before map lookup. Twelve Data receives the full string. Tickers are stored and displayed exactly as entered.

**Atomic share % recomputation**: After every price refresh, `current_share_percent` must be recomputed for ALL user assets within the same transaction (total portfolio value changes when any price changes). Only assets with a non-null `current_price_pln` contribute to the total. Assets with null price retain null `current_share_percent`.

**Twelve Data batch response shape**: A single-symbol `/price` call returns `{"price": "200.99"}` (no ticker key). A multi-symbol call returns `{"AAPL": {"price": "200.99"}, "PKN/XWAR": {"price": "52.50"}}`. The service must detect which shape it received (check if the JSON root has a `"price"` key directly vs ticker keys).

**CoinGecko Demo API key**: When `coingecko.api.key` is blank (local dev fallback), omit the `x-cg-demo-api-key` header entirely — CoinGecko's public endpoint works without a key but is rate-limited. Do not send an empty header.

---

## Phase 1: Database Migration (V10)

### Overview

Creates `portfolio_assets` table with precision-appropriate decimal columns for a crypto/stock mixed portfolio. Enforces one position per ticker per user via a UNIQUE constraint. All price columns are nullable — populated later by the price refresh service.

### Changes Required:

#### 1. Flyway migration V10

**File**: `src/main/resources/db/migration/V10__create_portfolio_assets_table.sql`

**Intent**: Additive migration — new table only, no changes to existing tables.

**Contract**: Table `portfolio_assets` with columns:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `ticker VARCHAR(20) NOT NULL`
- `asset_group VARCHAR(100) NOT NULL`
- `shares DECIMAL(20, 8) NOT NULL` — 8 decimal places for crypto precision
- `avg_buy_price_pln DECIMAL(20, 4) NOT NULL`
- `avg_buy_price_asset_currency DECIMAL(20, 8) NOT NULL`
- `purchase_value_pln DECIMAL(20, 4) NOT NULL`
- `purchase_value_asset_currency DECIMAL(20, 8) NOT NULL`
- `purchase_share_percent DECIMAL(7, 4)` — nullable, user-provided
- `current_price_usd DECIMAL(20, 8)` — nullable, set by price service
- `current_price_pln DECIMAL(20, 4)` — nullable, set by price service
- `current_share_percent DECIMAL(7, 4)` — nullable, recomputed on each price refresh
- `price_last_updated_at TIMESTAMPTZ` — nullable
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `CONSTRAINT uq_portfolio_user_ticker UNIQUE (user_id, ticker)`
- `CREATE INDEX idx_portfolio_assets_user_id ON portfolio_assets(user_id)`

### Success Criteria:

#### Automated Verification:

- `./mvnw test` — all existing tests still pass; `@SpringBootTest` context loads confirming V10 applied cleanly against Testcontainers PostgreSQL

#### Manual Verification:

- Connect to local PostgreSQL; confirm `portfolio_assets` table exists with correct columns, `uq_portfolio_user_ticker` UNIQUE constraint, and `idx_portfolio_assets_user_id` index

**Implementation Note**: Pause here to verify schema manually before proceeding.

---

## Phase 2: Backend CRUD

### Overview

Full domain implementation: entity, repository, service, controller, DTOs, exceptions. Follows the `transaction/` package conventions exactly. Manual creates return 409 on duplicate ticker. Integration tests cover CRUD, auth boundary, cross-user isolation.

### Changes Required:

#### 1. Entity

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioAsset.java`

**Intent**: JPA entity for `portfolio_assets`. Mirrors `Transaction.java` — UUID PK via `GenerationType.UUID`, `@ManyToOne(fetch = LAZY)` to `User`, `@PrePersist` sets `createdAt`.

**Contract**: All fields match V10 schema. `priceLastUpdatedAt` and `createdAt` are `Instant`. Nullable schema columns have nullable Java types. No setter for `id` or `createdAt`. No `UserDetails` mixing (lessons.md). Private methods below public.

#### 2. Repository

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioAssetRepository.java`

**Intent**: Spring Data JPA repository with the project's security-scoping pattern (disabled `findById`) plus the additional lookup methods needed by the price service and CSV import.

**Contract**: Extends `JpaRepository<PortfolioAsset, UUID>`. Override `findById` to throw `UnsupportedOperationException`. Methods:
- `List<PortfolioAsset> findAllByUserOrderByCreatedAtDesc(User user)`
- `Optional<PortfolioAsset> findByIdAndUser(UUID id, User user)`
- `Optional<PortfolioAsset> findByUserAndTicker(User user, String ticker)` — used for duplicate check and UPSERT
- `@Query("SELECT MAX(a.priceLastUpdatedAt) FROM PortfolioAsset a WHERE a.user = :user") Optional<Instant> findMaxPriceLastUpdatedAtByUser(@Param("user") User user)` — throttle check

#### 3. DTOs

**Files**: `src/main/java/com/example/finance_hq/portfolio/dto/CreatePortfolioAssetRequest.java`, `UpdatePortfolioAssetRequest.java`, `PortfolioAssetResponse.java`

**Intent**: Three Java records following the project DTO convention. Create record uses Jakarta validation. Response record includes `currentValuePln` computed from `shares × currentPricePln` (null if `currentPricePln` is null).

**Contract**:
- `CreatePortfolioAssetRequest`: `@NotBlank @Size(max=20) String ticker`; `@NotBlank @Size(max=100) String assetGroup`; `@NotNull @DecimalMin("0.00000001") BigDecimal shares`; `@NotNull @DecimalMin("0.00000001") BigDecimal avgBuyPricePln`; `@NotNull @DecimalMin("0.00000001") BigDecimal avgBuyPriceAssetCurrency`; `@NotNull @DecimalMin("0.00000001") BigDecimal purchaseValuePln`; `@NotNull @DecimalMin("0.00000001") BigDecimal purchaseValueAssetCurrency`; `@DecimalMin("0") @DecimalMax("100") BigDecimal purchaseSharePercent` (nullable).
- `UpdatePortfolioAssetRequest`: same fields, all nullable; at least one non-null enforced in service layer.
- `PortfolioAssetResponse`: all entity fields plus computed `BigDecimal currentValuePln`. Static factory `from(PortfolioAsset a)` — computes `currentValuePln` as `shares.multiply(currentPricePln).setScale(4, RoundingMode.HALF_UP)` when `currentPricePln` is non-null, else null.

#### 4. Exception classes

**Files**: `src/main/java/com/example/finance_hq/portfolio/InvalidPortfolioAssetException.java`, `PortfolioAssetNotFoundException.java`

**Intent**: Runtime exceptions following `InvalidTransactionException` and `TransactionNotFoundException`.

**Contract**: Both extend `RuntimeException`. `InvalidPortfolioAssetException(String message)`. `PortfolioAssetNotFoundException(UUID id)` — message: `"Portfolio asset not found: " + id`.

#### 5. Service

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioAssetService.java`

**Intent**: CRUD business logic. `create` rejects duplicate tickers with 409. `update` is partial (at least one field required). Price-related fields are not settable via CRUD — they belong to the price refresh service exclusively.

**Contract**: `@Service`. Public methods (private below public — lessons.md):
- `@Transactional(readOnly=true) List<PortfolioAssetResponse> findAll(User user)`
- `@Transactional PortfolioAssetResponse create(User user, CreatePortfolioAssetRequest req)` — checks `findByUserAndTicker`; throws `InvalidPortfolioAssetException("Position for {ticker} already exists. Use PATCH to update.")` if present
- `@Transactional PortfolioAssetResponse update(User user, UUID id, UpdatePortfolioAssetRequest req)` — fetches via `findByIdAndUser` or throws `PortfolioAssetNotFoundException`; validates at least one field non-null
- `@Transactional void delete(User user, UUID id)` — fetches via `findByIdAndUser` then `repository.delete(entity)` (not `deleteById` — see lessons.md)

#### 6. Controller

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioAssetController.java`

**Intent**: REST endpoints for portfolio CRUD at `/api/portfolio`. Mirrors `TransactionController` structure. Path constant extracted as a named field (lessons.md).

**Contract**: `@RestController @RequestMapping("/api/portfolio")`. Constant: `static final String BASE_PATH = "/api/portfolio"`. Endpoints:
- `GET /api/portfolio` → `200 List<PortfolioAssetResponse>`
- `POST /api/portfolio` → `201 PortfolioAssetResponse` (+ `@Valid`)
- `PATCH /api/portfolio/{id}` → `200 PortfolioAssetResponse` (+ `@Valid`)
- `DELETE /api/portfolio/{id}` → `204 No Content`
All inject `@AuthenticationPrincipal User user`.

#### 7. GlobalExceptionHandler additions

**File**: `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java`

**Intent**: Add handlers for the two new exceptions. `InvalidPortfolioAssetException` → 409 Conflict. `PortfolioAssetNotFoundException` → 404 Not Found.

**Contract**: Two new `@ExceptionHandler` methods returning `ResponseEntity<ProblemDetail>`, following the existing handler pattern.

#### 8. SecurityConfig update

**File**: `src/main/java/com/example/finance_hq/security/SecurityConfig.java`

**Intent**: Add `/portfolio` to the SPA `permitAll` routes so Angular's router can serve the page shell without triggering a 401 before the auth interceptor attaches the token.

**Contract**: Add `"GET /portfolio"` to the existing SPA `requestMatchers` block alongside `/dashboard`, `/obligations`, `/transactions`.

#### 9. Integration tests

**File**: `src/test/java/com/example/finance_hq/portfolio/PortfolioAssetControllerIntegrationTest.java`

**Intent**: Full integration test following `TransactionControllerIntegrationTest` conventions. Covers all functional and security requirements for the CRUD surface.

**Contract**: `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Transactional`. Test cases: empty list returns 200 with `[]`; POST creates and returns 201; duplicate ticker returns 409 ProblemDetail; GET returns created asset; PATCH updates fields; DELETE returns 204 and asset is gone; 401 without auth token; cross-user isolation (user A's assets invisible to user B). Uses `BASE_PATH` constant. Registers test users with timestamp-suffixed emails to avoid collisions.

#### 10. HTTP test file

**File**: `src/test/http-test-requests/portfolio.http`

**Intent**: Manual test file for all portfolio CRUD endpoints (lessons.md — required for every new endpoint group).

**Contract**: Variables at top: `baseUrl`, `token`. Sections: `### List`, `### Create`, `### Update`, `### Delete`, `### Duplicate ticker (expect 409)`.

### Success Criteria:

#### Automated Verification:

- `./mvnw test` — all tests pass including new `PortfolioAssetControllerIntegrationTest`

#### Manual Verification:

- All scenarios in `portfolio.http` return expected status codes and bodies
- POST with duplicate ticker returns `409` ProblemDetail with descriptive message
- Cross-user isolation confirmed: asset created as user A is not returned for user B

**Implementation Note**: Pause here to run manual verification before proceeding to Phase 3.

---

## Phase 3: Price Refresh Service

### Overview

Implements price fetching infrastructure: `CoinGeckoIdMapper` for crypto ticker routing, `PortfolioPriceService` that batches calls to Twelve Data and CoinGecko, and `PortfolioPriceController` exposing `POST /api/portfolio/refresh-prices`. The endpoint returns the full updated portfolio list — the frontend uses this response for the initial data load, eliminating a separate GET.

### Changes Required:

#### 1. Application properties

**File**: `src/main/resources/application.properties`

**Intent**: Add Twelve Data and CoinGecko credentials following the `${VAR:default}` fallback pattern (lessons.md). The `demo` key activates Twelve Data's public demo mode for local dev.

**Contract**: Add:
```properties
twelvedata.api.key=${TWELVEDATA_API_KEY:demo}
twelvedata.base.url=https://api.twelvedata.com
coingecko.api.key=${COINGECKO_API_KEY:}
coingecko.base.url=https://pro-api.coingecko.com
portfolio.price.stale-minutes=15
```

#### 2. CoinGecko ID mapper

**File**: `src/main/java/com/example/finance_hq/portfolio/CoinGeckoIdMapper.java`

**Intent**: Hardcoded lookup table from exchange tickers to CoinGecko internal IDs. Strips `/EXCHANGE` suffix before lookup so `BTC/CRYPTO` and `BTC` both resolve. Returns `Optional.empty()` for unknown tickers — callers log WARN and skip pricing for that asset without failing the whole refresh.

**Contract**: `@Component`. Single public method: `Optional<String> toId(String ticker)`. Implementation: strip everything from the first `/` onwards; look up the result in the internal map. Map must cover at minimum: BTC→bitcoin, ETH→ethereum, SOL→solana, BNB→binancecoin, XRP→ripple, ADA→cardano, AVAX→avalanche-2, DOGE→dogecoin, DOT→polkadot, MATIC→matic-network, LINK→chainlink, UNI→uniswap, LTC→litecoin, ATOM→cosmos, FTM→fantom, NEAR→near, ALGO→algorand, VET→vechain, ICP→internet-computer, FIL→filecoin, SHIB→shiba-inu.

#### 3. Price refresh DTO

**File**: `src/main/java/com/example/finance_hq/portfolio/dto/PriceRefreshResponse.java`

**Intent**: Wraps the refresh result so the frontend knows whether prices actually updated (for UX feedback).

**Contract**: `record PriceRefreshResponse(boolean refreshed, List<PortfolioAssetResponse> assets)`.

#### 4. Price refresh service

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioPriceService.java`

**Intent**: Orchestrates the full price refresh cycle. Throttle check first; then partition assets by price source; batch-call Twelve Data and CoinGecko; update rows; recompute `current_share_percent` atomically. Returns a `PriceRefreshResponse` regardless of whether a refresh occurred — the `refreshed` flag tells the frontend whether prices are new.

**Contract**: `@Service`. One public method: `@Transactional PriceRefreshResponse refreshIfStale(User user)`. Internal flow:
1. Load all user assets via `findAllByUserOrderByCreatedAtDesc`
2. If empty: return `PriceRefreshResponse(false, List.of())`
3. Check `findMaxPriceLastUpdatedAtByUser(user)`. If non-null and `Instant.now().minus(staleMinutes, MINUTES).isBefore(maxUpdatedAt)`: skip fetch, return `PriceRefreshResponse(false, assets as responses)`
4. Partition assets: `coinGeckoIdMapper.toId(a.getTicker()).isPresent()` → crypto; else → security
5. If crypto assets non-empty: call CoinGecko `GET /api/v3/simple/price?ids={commaSeparatedIds}&vs_currencies=usd,pln`; add `x-cg-demo-api-key` header only if `coingecko.api.key` is non-blank
6. If security assets non-empty: build symbol string (full ticker e.g. `PKN/XWAR`); call Twelve Data `GET /price?symbol={symbols}&apikey={key}`; handle single-symbol (`{"price": "..."}`) vs multi-symbol (`{"{ticker}": {"price": "..."}, ...}`) response shapes
7. For each asset: look up its price in the respective response; if not found, log WARN and leave existing price unchanged; if found, set `currentPriceUsd`, `currentPricePln`, `priceLastUpdatedAt = Instant.now()`
8. Recompute `currentSharePercent` for all assets: sum `shares × currentPricePln` for assets where `currentPricePln` is non-null → total; divide each asset's current value by total × 100; round to scale 4; set null for assets with null `currentPricePln`
9. `repository.saveAll(assets)`
10. Return `PriceRefreshResponse(true, updated assets as responses)`

Uses `RestClient` (Spring's fluent HTTP client). `@Value` injection for URL and key properties.

#### 5. Price refresh controller

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioPriceController.java`

**Intent**: Single endpoint that triggers `refreshIfStale` and returns the result. The frontend uses the returned asset list to populate the portfolio table — no separate GET needed after navigation.

**Contract**: `@RestController`. `POST /api/portfolio/refresh-prices` → `200 PriceRefreshResponse`. Injects `@AuthenticationPrincipal User user`. Delegates entirely to `PortfolioPriceService.refreshIfStale(user)`.

#### 6. Unit tests

**File**: `src/test/java/com/example/finance_hq/portfolio/PortfolioPriceServiceTest.java`

**Intent**: Tests for routing, throttle, and share-% arithmetic. Uses Testcontainers + `@SpringBootTest` to exercise the real repository; mocks `RestClient` calls.

**Contract**: `@SpringBootTest`, `@Transactional`. Test cases: crypto-only portfolio calls CoinGecko only (Twelve Data mock not called); securities-only calls Twelve Data only; mixed portfolio calls both; MAX within 15 min → `refreshed: false`, no API calls; unknown ticker (not in CoinGecko map, absent from Twelve Data response) → other assets still update; `current_share_percent` computed correctly for a 3-asset portfolio (verified to 4 decimal places); CoinGecko empty-key omits the auth header.

#### 7. HTTP test file update

**File**: `src/test/http-test-requests/portfolio.http`

**Intent**: Add the `POST /api/portfolio/refresh-prices` test request.

**Contract**: New section `### Refresh prices`. Call the endpoint twice in quick succession — first should return `"refreshed": true`; second (within 15 min) should return `"refreshed": false`.

### Success Criteria:

#### Automated Verification:

- `./mvnw test` — all tests pass including `PortfolioPriceServiceTest`

#### Manual Verification:

- Set `TWELVEDATA_API_KEY` and `COINGECKO_API_KEY` in `application-local.properties`
- Add one security asset (`AAPL`) and one crypto asset (`BTC`) via `portfolio.http`
- Call `POST /api/portfolio/refresh-prices` → `"refreshed": true` and prices populate
- Call again immediately → `"refreshed": false` and same prices returned
- Add a `PKN/XWAR` asset and verify `currentPricePln` populates (GPW data freshness check)

**Implementation Note**: GPW free-tier data freshness must be confirmed during market hours before marking Phase 3 complete. If Twelve Data free tier returns stale GPW data, document this limitation in `research.md` as an open risk for the user.

---

## Phase 4: CSV Import

### Overview

Adds multipart CSV import. Import UPSERTs on (user_id, ticker) — re-importing is idempotent. All-or-nothing: any row error aborts the entire import and returns a 422 with per-row error details. Apache Commons CSV 1.12.0 handles header-flexible parsing. The `current_price` column, if present, is silently ignored — prices come from APIs only.

### Changes Required:

#### 1. pom.xml dependency

**File**: `pom.xml`

**Intent**: Add Apache Commons CSV. No BOM entry — pin version explicitly.

**Contract**: Inside `<dependencies>`:
```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-csv</artifactId>
    <version>1.12.0</version>
</dependency>
```

#### 2. Multipart size limits

**File**: `src/main/resources/application.properties`

**Intent**: Reject oversized uploads before the controller is invoked. Spring raises `MaxUploadSizeExceededException` automatically.

**Contract**: Add:
```properties
spring.servlet.multipart.max-file-size=5MB
spring.servlet.multipart.max-request-size=5MB
```

#### 3. RowError and CsvImportResult records

**Files**: `src/main/java/com/example/finance_hq/portfolio/dto/RowError.java` and `CsvImportResult.java`

**Intent**: Carry per-row parse errors back to the controller for a structured 422 response.

**Contract**: `record RowError(int rowNumber, String column, String message)`. `record CsvImportResult(int importedCount, List<RowError> rowErrors)`.

#### 4. InvalidCsvException

**File**: `src/main/java/com/example/finance_hq/portfolio/InvalidCsvException.java`

**Intent**: Hard-stop exception for malformed file or missing required headers. Returned as 400.

**Contract**: Extends `RuntimeException`. Constructor: `InvalidCsvException(String message)`.

#### 5. CSV import service

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioCsvImportService.java`

**Intent**: Parse, validate, and UPSERT CSV rows for portfolio assets. Fail-fast: if any row has errors, no rows are persisted. Tolerates European decimal commas. The `current_price` column is parsed but discarded — prices are authoritative from APIs.

**Contract**: `@Service`. Public method: `@Transactional CsvImportResult importCsv(User user, MultipartFile file)`.

CSVFormat: `DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true).setIgnoreEmptyLines(true).build()`.

Required CSV headers (case-insensitive): `asset` (maps to `ticker`), `shares`, `avg_buy_price_pln`, `avg_buy_price_asset_currency`, `purchase_value_pln`, `purchase_value_asset_currency`, `asset_group`. Also accept `group_of_asset` as an alias for `asset_group`. Throw `InvalidCsvException` if any required header is absent (list the missing ones in the message).

Optional headers (silently ignored if absent): `purchase_share_percent`, `current_price`.

Per-row: skip via `record.isEmpty()`; cap at 10,000 rows (throw `InvalidCsvException`); parse `BigDecimal` with `new BigDecimal(raw.replace(",", "."))` inside try/catch — add `RowError` on `NumberFormatException`; validate `asset` non-blank. Accumulate all row errors.

After iteration: if `rowErrors` non-empty → return `CsvImportResult(0, errors)` without persisting. If clean: for each row, `findByUserAndTicker(user, ticker)` — update all fields if found, create new entity if not — then `repository.saveAll(entities)`. Return `CsvImportResult(entities.size(), List.of())`.

Price-related fields (`currentPriceUsd`, `currentPricePln`, `currentSharePercent`, `priceLastUpdatedAt`) are never set from CSV — left null on new entities, left unchanged on existing ones.

#### 6. Import endpoint

**File**: `src/main/java/com/example/finance_hq/portfolio/PortfolioAssetController.java`

**Intent**: Add `POST /api/portfolio/import` accepting `multipart/form-data`. Content-type guard, empty file guard, delegates to `PortfolioCsvImportService`, returns structured response.

**Contract**: Add method `@PostMapping(value = "/import", consumes = MULTIPART_FORM_DATA_VALUE)`. Accepts `@RequestParam("file") MultipartFile file`. Content-type check: accept `text/csv`, `text/plain`, `application/csv` — reject others with `400 ProblemDetail`. Empty file check → `400`. On `CsvImportResult` with errors: `422 ProblemDetail` with `rowErrors` and `importedCount` set as properties. On success: `200 Map.of("importedCount", result.importedCount())`.

#### 7. GlobalExceptionHandler additions

**File**: `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java`

**Intent**: Handle `InvalidCsvException` → 400 ProblemDetail, `MaxUploadSizeExceededException` → 413 ProblemDetail.

**Contract**: Two new `@ExceptionHandler` methods. `MaxUploadSizeExceededException` handler sets title "File Too Large" and HTTP 413.

#### 8. Integration tests update

**File**: `src/test/java/com/example/finance_hq/portfolio/PortfolioAssetControllerIntegrationTest.java`

**Intent**: Add import endpoint tests to the existing integration test class.

**Contract**: New test cases: valid CSV imports N assets (200 with count); re-import same CSV → same N assets (UPSERT, not N×2); missing required header → 400 with missing column names; bad decimal in one row → 422 with `rowErrors` array; 401 without auth.

#### 9. Unit tests for CSV service

**File**: `src/test/java/com/example/finance_hq/portfolio/PortfolioCsvImportServiceTest.java`

**Intent**: Pure unit tests exercising the parsing and validation logic. No Spring context — inject mocked repository.

**Contract**: `@ExtendWith(MockitoExtension.class)`. Build `MockMultipartFile` from inline CSV strings. Cases: valid CSV imports correctly; optional `purchase_share_percent` present; missing required header throws `InvalidCsvException`; bad decimal produces `RowError` with correct row number and column; UPSERT: same ticker twice — second import updates the row; row cap at 10,001 → `InvalidCsvException`; European comma decimal `"1.234,56"` → `1234.56`.

#### 10. HTTP test file update

**File**: `src/test/http-test-requests/portfolio.http`

**Intent**: Add import test requests.

**Contract**: New section `### Import CSV` with multipart POST using a valid CSV string and a separate request with a row containing a bad decimal.

### Success Criteria:

#### Automated Verification:

- `./mvnw test` — all tests pass including `PortfolioCsvImportServiceTest` and updated integration tests

#### Manual Verification:

- Import a valid 5-row CSV → GET returns 5 assets
- Re-import same CSV → still 5 assets (not 10)
- Import CSV with one row having `"abc"` as `shares` → 422 with row number and column in response
- Upload a file > 5 MB → 413 ProblemDetail
- Upload a `.json` file with `Content-Type: application/json` → 400 ProblemDetail

**Implementation Note**: Confirm UPSERT produces correct data by checking DB state (or via GET) after re-import before proceeding to Phase 5.

---

## Phase 5: Frontend — Portfolio Page

### Overview

Angular portfolio page with signal-based reactive state, data table, add/edit/delete dialogs, and CSV import button. On `ngOnInit`, calls `POST /api/portfolio/refresh-prices` and uses the returned list as the initial data load (no separate GET). Shows a stale-price indicator when any asset's `priceLastUpdatedAt` is > 25 hours old.

### Changes Required:

#### 1. TypeScript model

**File**: `src/main/frontend/src/app/features/portfolio/portfolio.model.ts`

**Intent**: TypeScript interfaces matching `PortfolioAssetResponse` and the create/update DTOs.

**Contract**:
```typescript
export interface PortfolioAsset {
  id: string;
  ticker: string;
  assetGroup: string;
  shares: number;
  avgBuyPricePln: number;
  avgBuyPriceAssetCurrency: number;
  purchaseValuePln: number;
  purchaseValueAssetCurrency: number;
  purchaseSharePercent: number | null;
  currentPriceUsd: number | null;
  currentPricePln: number | null;
  currentValuePln: number | null;
  currentSharePercent: number | null;
  priceLastUpdatedAt: string | null;
  createdAt: string;
}
export type CreatePortfolioAssetDto = Omit<PortfolioAsset,
  'id' | 'createdAt' | 'currentPriceUsd' | 'currentPricePln' | 'currentValuePln' | 'currentSharePercent' | 'priceLastUpdatedAt'>;
export type UpdatePortfolioAssetDto = Partial<CreatePortfolioAssetDto>;
export interface PriceRefreshResponse { refreshed: boolean; assets: PortfolioAsset[]; }
```

#### 2. Portfolio service

**File**: `src/main/frontend/src/app/features/portfolio/portfolio.service.ts`

**Intent**: Stateless HTTP wrapper following `TransactionsService` pattern. `refreshPrices()` POSTs to the refresh endpoint and returns the `PriceRefreshResponse` (not just the asset list, so the component can show a "refreshed" toast).

**Contract**: `@Injectable({ providedIn: 'root' })`. `BASE = '/api/portfolio'`. Methods: `getAll(): Observable<PortfolioAsset[]>`; `create(dto: CreatePortfolioAssetDto): Observable<PortfolioAsset>`; `update(id: string, dto: UpdatePortfolioAssetDto): Observable<PortfolioAsset>`; `delete(id: string): Observable<void>`; `refreshPrices(): Observable<PriceRefreshResponse>` (POST to `${BASE}/refresh-prices`); `importCsv(file: File): Observable<{importedCount: number}>` (POST to `${BASE}/import` as `FormData` with `file` key).

#### 3. Portfolio component

**Files**: `src/main/frontend/src/app/features/portfolio/portfolio.component.ts` and `portfolio.component.html`

**Intent**: Main portfolio page. `ngOnInit` calls `refreshPrices()`, populates the table from the returned list, and shows a toast if `refreshed: true`. Uses Angular signals for reactive state. Computes `isPriceStale(asset)` to show a warning when prices are > 25 hours old.

**Contract**: Signals: `assets: signal<PortfolioAsset[]>([])`, `loading: signal<boolean>(false)`, `showAddEdit: signal<boolean>(false)`, `editingAsset: signal<PortfolioAsset | null>(null)`, `showDelete: signal<boolean>(false)`, `deletingAsset: signal<PortfolioAsset | null>(null)`.

Table columns (in order): Ticker, Group, Shares, Avg Buy PLN, Current Price PLN, Current Value PLN, Purchase %, Current %, Last Updated. "Last Updated" cell shows relative time (e.g., "2h ago") and an amber warning icon if stale.

`isPriceStale(asset: PortfolioAsset): boolean` — returns true if `priceLastUpdatedAt` is non-null and the parsed date is more than 25 hours before `Date.now()`.

Import flow: `<input #csvInput type="file" accept=".csv" (change)="onFileSelected($event)">` triggered by a button click. On file select: call `portfolioService.importCsv(file)`. On success: show toast "Imported N positions", re-call `refreshPrices()` to reload. On 422 error: display per-row errors in a toast or inline error list beneath the import button.

On add/edit save: call `loadPortfolio()` (which calls `refreshPrices()` again — backend throttle prevents redundant API calls).

#### 4. Portfolio dialog (add/edit)

**Files**: `src/main/frontend/src/app/features/portfolio/portfolio-dialog/portfolio-dialog.component.ts` and `.html`

**Intent**: Reactive form for creating or editing a position. Follows `TransactionDialogComponent` structure.

**Contract**: Input `@Input() asset: PortfolioAsset | null` (null = create mode). Reactive form fields: ticker (required), assetGroup (required), shares (required, > 0), avgBuyPricePln (required, > 0), avgBuyPriceAssetCurrency (required, > 0), purchaseValuePln (required, > 0), purchaseValueAssetCurrency (required, > 0), purchaseSharePercent (optional, 0–100). Output `@Output() saved = new EventEmitter<void>()`. On submit: call `portfolioService.create()` or `portfolioService.update()` depending on mode; emit `saved` on success.

#### 5. Delete dialog

**Files**: `src/main/frontend/src/app/features/portfolio/delete-dialog/delete-dialog.component.ts` and `.html`

**Intent**: Confirmation dialog before deletion. Mirrors `delete-dialog` in obligations/transactions.

**Contract**: Input `@Input() asset: PortfolioAsset`. Output `@Output() confirmed = new EventEmitter<void>()`. On confirm: calls `portfolioService.delete(asset.id)`, emits `confirmed` on success.

#### 6. Routing

**File**: `src/main/frontend/src/app/app.routes.ts`

**Intent**: Add `/portfolio` route under the protected layout, lazily loaded.

**Contract**: Add to the protected layout's `children` array: `{ path: 'portfolio', loadComponent: () => import('./features/portfolio/portfolio.component').then(m => m.PortfolioComponent) }`.

#### 7. Sidebar nav item

**File**: `src/main/frontend/src/app/shared/layout/sidebar/sidebar.component.html`

**Intent**: Add "Portfolio" navigation item alongside Dashboard, Obligations, Transactions.

**Contract**: Add an `<a routerLink="/portfolio" routerLinkActive="active">Portfolio</a>` list item in the same style as existing nav items.

### Success Criteria:

#### Automated Verification:

- `ng build` (from `src/main/frontend/`) completes without TypeScript errors

#### Manual Verification:

- Navigate to `/portfolio` — empty state renders (no assets yet)
- Add a position via dialog → appears in table
- Edit a position → updated fields reflected immediately
- Delete a position → removed after confirmation dialog
- Import a valid CSV → assets appear; re-import same CSV → same count (UPSERT)
- Import invalid CSV → per-row error message shown
- After 15+ min: navigate away to Obligations and back to Portfolio → price refresh triggers, `priceLastUpdatedAt` updates; toast shows "Prices refreshed"
- Within 15 min: navigate away and back → no toast (prices not refreshed, existing prices shown)
- Stale indicator (amber) appears on an asset when `priceLastUpdatedAt` is > 25h old
- No regressions on Dashboard, Obligations, Transactions pages

**Implementation Note**: Run the full manual test suite above. Pay special attention to the price refresh toast behavior and the stale indicator. Mark the change complete only after all scenarios pass.

---

## Testing Strategy

### Unit Tests:

- `PortfolioPriceServiceTest`: single-symbol vs multi-symbol Twelve Data response shape; crypto/securities partitioning; 15-min throttle; unknown ticker skip; share % arithmetic for 3-asset portfolio
- `PortfolioCsvImportServiceTest`: header-flexible parsing; optional column handling; European decimal comma; UPSERT on re-import; row count cap; fail-fast on row errors

### Integration Tests:

- `PortfolioAssetControllerIntegrationTest`: full CRUD, 409 duplicate, 401 boundary, cross-user isolation, import happy path, import missing header, import bad decimal row

### Manual Testing Steps:

1. `./mvnw test` — confirm all tests pass
2. Start backend with `--spring.profiles.active=local`; confirm V10 migration applied
3. Use `portfolio.http` to test CRUD, import, and refresh endpoints with real API keys
4. `ng serve` (from `src/main/frontend/`); log in
5. Add AAPL, BTC, and PKN/XWAR manually; call refresh; verify prices populate
6. Import a CSV; verify UPSERT on re-import
7. Navigate away and back within 15 min; verify no re-fetch
8. Manually set `price_last_updated_at` in DB to > 25h ago; confirm stale indicator appears

## Performance Considerations

- Twelve Data batch: all user securities in one HTTP request — 800 req/day free tier not a concern for a personal tracker (< 5% usage for 20 tickers refreshing 3×/day)
- CoinGecko batch: up to 500 IDs per call; one request covers any realistic personal portfolio
- `saveAll`: single batch insert/update for all assets in the price refresh transaction
- CSV import: in-memory parsing, no temp files; 10,000-row cap prevents heap exhaustion

## Migration Notes

V10 is purely additive. No existing data is modified. Flyway baseline (`baseline-on-migrate=true`, `baseline-version=0`) is already configured. No rollback script needed for a new table addition.

## References

- Research: `context/changes/portfolio-asset-agregator-with-csv-import/research.md`
- CRUD blueprint: `src/main/java/com/example/finance_hq/transaction/TransactionController.java`
- Repository security pattern: `src/main/java/com/example/finance_hq/transaction/TransactionRepository.java`
- Exception handler: `src/main/java/com/example/finance_hq/auth/exception/GlobalExceptionHandler.java`
- Lessons: `context/foundation/lessons.md`
- Twelve Data docs: `https://twelvedata.com/docs`
- CoinGecko Demo API: `https://docs.coingecko.com/reference/simple-price`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Database Migration (V10)

#### Automated

- [x] 1.1 `./mvnw test` — all existing tests pass; @SpringBootTest context loads confirming V10 migration applied — 92d02ad

#### Manual

- [x] 1.2 Connect to local PostgreSQL; confirm `portfolio_assets` table schema, UNIQUE constraint, and index exist — 92d02ad

### Phase 2: Backend CRUD

#### Automated

- [x] 2.1 `./mvnw test` — all tests pass including `PortfolioAssetControllerIntegrationTest` — 6be104f

#### Manual

- [x] 2.2 All CRUD scenarios in `portfolio.http` return expected status codes — 6be104f
- [x] 2.3 Duplicate ticker POST returns 409 ProblemDetail with descriptive message — 6be104f
- [x] 2.4 Cross-user isolation confirmed — 6be104f

### Phase 3: Price Refresh Service

#### Automated

- [x] 3.1 `./mvnw test` — all tests pass including `PortfolioPriceServiceTest` — 81b2949

#### Manual

- [x] 3.2 Refresh with real API keys populates prices for AAPL (Twelve Data) and BTC (CoinGecko) — 81b2949
- [x] 3.3 Second refresh within 15 min returns `"refreshed": false` — 81b2949
- [x] 3.4 PKN/XWAR asset receives a price from Twelve Data (GPW freshness verified) — 81b2949

### Phase 4: CSV Import

#### Automated

- [x] 4.1 `./mvnw test` — all tests pass including `PortfolioCsvImportServiceTest` and updated integration tests

#### Manual

- [x] 4.2 Valid CSV import → correct asset count returned
- [x] 4.3 Re-import same CSV → same count (UPSERT confirmed)
- [x] 4.4 CSV with bad decimal → 422 with row number and column
- [ ] 4.5 File > 5 MB → 413 ProblemDetail

### Phase 5: Frontend — Portfolio Page

#### Automated

- [ ] 5.1 `ng build` completes without TypeScript errors

#### Manual

- [ ] 5.2 Add/edit/delete positions work end-to-end
- [ ] 5.3 CSV import and re-import work correctly from the UI
- [ ] 5.4 Price refresh triggers on navigation to portfolio page; toast shown on fresh refresh
- [ ] 5.5 Within-15-min navigation does not re-fetch prices
- [ ] 5.6 Stale price indicator visible when prices > 25h old
- [ ] 5.7 No regressions on Dashboard, Obligations, Transactions pages
