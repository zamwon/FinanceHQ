<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Portfolio Asset Tracker with Manual Entry and CSV Import

- **Plan**: context/changes/portfolio-asset-agregator-with-csv-import/plan.md
- **Scope**: Phase 2 of 5 (Backend CRUD)
- **Date**: 2026-06-10
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical · 4 warnings · 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Wrong HTTP status for empty-body PATCH

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality / Pattern Consistency
- **Location**: PortfolioAssetService.java:55 + GlobalExceptionHandler.java
- **Detail**: `InvalidPortfolioAssetException` is used for two distinct scenarios: (1) duplicate ticker on create → 409 Conflict ✓ correct; (2) "at least one field required" on update → 409 Conflict ✗ wrong. The handler maps the entire exception class to 409 uniformly. Blueprint: `InvalidTransactionException` and `InvalidObligationException` both map to 400 exclusively.
- **Fix A ⭐ Recommended**: Split exceptions — introduce `PortfolioAssetValidationException` → 400 for the update-guard throw; keep `InvalidPortfolioAssetException` → 409 for duplicate ticker only.
  - Strength: Matches blueprint; both scenarios get correct HTTP codes.
  - Tradeoff: One more exception class + handler method.
  - Confidence: HIGH — identical split exists in codebase.
  - Blind spot: None significant.
- **Fix B**: Remap `InvalidPortfolioAssetException` → 400; introduce `DuplicatePortfolioAssetException` → 409.
  - Strength: Keeps "Invalid" naming consistent with 400 usage elsewhere.
  - Tradeoff: Wider change — renames exception used by create().
  - Confidence: MEDIUM.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A — added PortfolioAssetValidationException → 400; update() guard now throws it instead of InvalidPortfolioAssetException

### F2 — Ticker update has no duplicate check

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: PortfolioAssetService.java:62-68
- **Detail**: `update()` allows setting ticker to a value already owned by the user. `create()` has a `findByUserAndTicker` guard; `update()` does not. Patching `BTC` to `ticker=AAPL` when user already owns `AAPL` will either hit the DB UNIQUE constraint (opaque 409 from `DataIntegrityViolationException`) or produce a confusing error.
- **Fix**: In `update()`, when `req.ticker() != null` and differs from `asset.getTicker()`, call `findByUserAndTicker(user, req.ticker())` and throw (the 409 exception from F1) if a conflict exists.
  - Strength: Closes gap; consistent message with create() case. One extra DB read on ticker-changing PATCHes only.
  - Confidence: HIGH — trivial guard, same pattern as create().
  - Blind spot: None significant.
- **Decision**: FIXED — added duplicate-ticker guard in update() before setTicker()

### F3 — UUID leaks in 404 response body (inconsistent with blueprint)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality / Pattern Consistency
- **Location**: GlobalExceptionHandler.java + PortfolioAssetNotFoundException.java:6
- **Detail**: `handlePortfolioAssetNotFound` passes `ex.getMessage()` to `ProblemDetail` detail — surfaces `"Portfolio asset not found: <uuid>"` in 404 body. Blueprint handlers (`handleObligationNotFound`, `handleTransactionNotFound`) use hardcoded strings, never exposing internal IDs.
- **Fix**: Change handler to `ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Portfolio asset not found")` — hardcoded string matching blueprint.
- **Decision**: FIXED — handler now uses hardcoded "Portfolio asset not found" string

### F4 — findAll is unbounded — no pagination cap

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: PortfolioAssetRepository.java:21 / PortfolioAssetService.java:24
- **Detail**: `findAllByUserOrderByCreatedAtDesc` returns a raw `List` with no cap. Phase 4 CSV import allows up to 10,000 rows. Blueprint: `TransactionService.findAll()` uses `PageRequest.of(0, 200, ...)`.
- **Fix A ⭐ Recommended**: Add `PageRequest.of(0, 500, Sort.by(DESC, "createdAt"))` in service; change repository to accept `Pageable`.
  - Strength: Prevents heap pressure before Phase 4 lands. Consistent with blueprint.
  - Tradeoff: Users with >500 assets see truncated list without pagination UI.
  - Confidence: HIGH.
  - Blind spot: Frontend doesn't implement pagination controls yet.
- **Fix B**: Leave unbounded; add cap in Phase 4.
  - Strength: No change today.
  - Tradeoff: Risk materialises at CSV import time if forgotten.
  - Confidence: LOW.
  - Blind spot: Phase 4 review may not connect the phases.
- **Decision**: FIXED via Fix A — service.findAll() now uses PageRequest.of(0, 500, Sort.by(DESC, "createdAt")); repository has both List<> variant (for Phase 3 price service) and Page<> variant (for CRUD cap)

### F5 — Phase 3 files untracked in working tree (expected parallel work)

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/java/com/example/finance_hq/portfolio/ (untracked)
- **Detail**: `CoinGeckoIdMapper`, `PortfolioPriceService`, `PortfolioPriceController`, `PortfolioPriceConfig`, `PriceRefreshResponse`, `PortfolioPriceServiceTest` are untracked — Phase 3 implemented in parallel by another context. 7 Phase 3 tests already pass. Intentional parallel work.
- **Fix**: None — commit Phase 3 via its own ritual after Phase 3 review.
- **Decision**: SKIPPED — intentional parallel work; Phase 3 committed via its own ritual

### F6 — Missing tests: empty-body PATCH and ticker-collision PATCH

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: PortfolioAssetControllerIntegrationTest.java
- **Detail**: No test for `PATCH {}` (empty body) and no test for PATCH with ticker already owned by same user. Fixing F1/F2 without these tests leaves no regression protection.
- **Fix**: Add `update_409_emptyBody` and `update_409_duplicateTicker` tests after fixing F1 and F2.
- **Decision**: FIXED — added update_400_emptyBody and update_409_duplicateTicker test cases; 134 tests pass

### F7 — BASE_PATH is package-private; should be public

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: PortfolioAssetController.java:19
- **Detail**: `static final String BASE_PATH` has package-private visibility. Works because test is in the same package, but would break if test is moved to a different package.
- **Fix**: Add `public` modifier: `public static final String BASE_PATH = "/api/portfolio";`
- **Decision**: FIXED — added public modifier to BASE_PATH in PortfolioAssetController

### F8 — Floating-point doubleValue() for shares assertion is fragile

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: PortfolioAssetControllerIntegrationTest.java:168
- **Detail**: `((Number) body.get("shares")).doubleValue()).isEqualTo(2.5)` works for 2.5 (exactly representable) but would fail for values like 2.1 (crypto precision to 8 decimal places).
- **Fix**: `assertThat(body.get("shares").toString()).isEqualTo("2.5")`
- **Decision**: FIXED — changed to .toString() assertion in update_200_fieldsChanged test
