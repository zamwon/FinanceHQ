# Quick Fix Guide - Sentry Tunnel Tests (5-10 minutes)

## TL;DR

Two issues, two fixes:

1. **Rate limiter shared state** → Add reset method + call in @BeforeEach
2. **Empty body exception** → Add null check before processing

---

## Fix 1: Rate Limiter Reset (3 test failures)

### In SentryTunnelController.java (after line 41)

```java
// Add this method to allow tests to reset the rate limiter
public void resetRateLimiter() {
    requestCount.set(0);
    windowStart.set(System.currentTimeMillis());
}
```

### In SentryTunnelControllerIntegrationTest.java

**Add field** (after line 28):
```java
@Autowired
private SentryTunnelController sentryTunnelController;
```

**Add reset call** (in setup() method, after line 46):
```java
// Reset rate limiter so tests don't interfere with each other
sentryTunnelController.resetRateLimiter();
```

---

## Fix 2: Empty Body Handling (1 test failure)

### In SentryTunnelController.java (start of tunnel() method, line 44)

```java
@PostMapping
public ResponseEntity<Void> tunnel(@RequestBody String body) {
    // ADD THIS - Handle empty body before rate limiting
    if (body == null || body.trim().isEmpty()) {
        log.warn("Invalid Sentry envelope: empty body");
        return ResponseEntity.badRequest().build();
    }

    // ... rest of existing code ...
}
```

---

## Verify

```bash
cd /home/bkarnecki/Downloads/10xDevs/FinanceHQ

# Run tests
./mvnw test

# Look for:
# Tests run: 84, Failures: 0, Errors: 0 ✅
```

---

## Files to Edit

1. `src/main/java/com/example/finance_hq/web/SentryTunnelController.java`
   - Add `resetRateLimiter()` method
   - Add empty body check in `tunnel()` method

2. `src/test/java/com/example/finance_hq/web/SentryTunnelControllerIntegrationTest.java`
   - Add `@Autowired SentryTunnelController` field
   - Add reset call in `setup()` method

---

## Expected Results

Before:
```
Tests run: 84, Failures: 3, Errors: 0
❌ testTunnelWithEmptyBody_Returns400: expected:<400> but was:<500>
❌ testTunnelWithMissingDsn_Returns400: expected:<400> but was:<429>
❌ testTunnelWithUnknownDsn_Returns400: expected:<400> but was:<429>
```

After:
```
Tests run: 84, Failures: 0, Errors: 0
✅ All tests pass
```

---

## Why This Works

- **Rate limiter reset**: Clears shared state before each test so tests don't interfere
- **Empty body check**: Prevents exception from being thrown, returns 400 as expected

---

## Don't Forget

After applying fixes:
```bash
git add -A
git commit -m "fix(sentry-monitoring): resolve test isolation issues (rate limiter reset, empty body handling)"
./mvnw test  # Verify
```

See `TEST_FAILURES_ANALYSIS.md` for deeper understanding.
