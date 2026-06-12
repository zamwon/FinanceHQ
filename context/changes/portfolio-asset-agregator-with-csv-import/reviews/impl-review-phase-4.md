<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Portfolio Asset Tracker with Manual Entry and CSV Import

- **Plan**: context/changes/portfolio-asset-agregator-with-csv-import/plan.md
- **Scope**: Phase 4 of 5 (CSV Import)
- **Date**: 2026-06-12
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical · 4 warnings · 2 observations

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

### F1 — purchaseSharePercent silently nulled on re-import when column absent

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (Data Safety)
- **Location**: PortfolioCsvImportService.java:85-108
- **Detail**: purchaseSharePercent was initialised to null and entity.setPurchaseSharePercent() was called unconditionally. Re-importing a CSV without the column wrote null over any existing value.
- **Fix**: Guard setter with `if (record.isMapped("purchase_share_percent"))` so existing values survive re-import when column absent.
- **Decision**: FIXED — added isMapped guard around setPurchaseSharePercent()

### F2 — Blank asset_group cell silently stored as empty string

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: PortfolioCsvImportService.java:139-143
- **Detail**: resolveAssetGroup() returned trimmed cell value with no blank check. Blank cell produced "" on entity bypassing validation.
- **Fix**: Added blank check after resolveAssetGroup() — emits RowError("asset_group", "Asset group must not be blank") and continues, mirroring ticker blank check.
- **Decision**: FIXED — added assetGroup.isBlank() guard with RowError

### F3 — Content-type check is client-controlled (advisory only)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Security)
- **Location**: PortfolioAssetController.java:67-75
- **Detail**: file.getContentType() returns client-declared value. Null content-type also bypassed the check. Real protection is Commons CSV parse failure.
- **Fix**: (1) Added contentType == null to rejection condition; (2) added comment clarifying this is a UX guard, not a security boundary.
- **Decision**: FIXED — null content-type now returns 400; advisory comment added

### F4 — No cross-user isolation test for /import endpoint

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: PortfolioAssetControllerIntegrationTest.java
- **Detail**: CRUD section had thorough cross-user isolation tests but import section had none.
- **Fix**: Added import_200_doesNotExposeOtherUsersAssets — user A imports CSV, GET as user B returns empty list.
- **Decision**: FIXED — isolation test added; 149 tests pass

### F5 — Row-cap check fires after full in-memory load (observation)

- **Severity**: OBSERVATION
- **Dimension**: Plan Adherence (minor drift)
- **Location**: PortfolioCsvImportService.java:44,56
- **Detail**: getRecords() materialises entire file before size check. Functional boundary (10,000) is correct. With 5 MB Spring limit, negligible practical difference.
- **Decision**: SKIPPED — no action needed

### F6 — record.values().length == 0 instead of idiomatic record.isEmpty() (observation)

- **Severity**: OBSERVATION
- **Dimension**: Plan Adherence (minor drift)
- **Location**: PortfolioCsvImportService.java:65
- **Detail**: With setIgnoreEmptyLines(true), this guard is effectively dead code. No functional difference.
- **Decision**: SKIPPED — no action needed
