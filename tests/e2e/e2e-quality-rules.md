# E2E Quality Rules — FinanceHQ

These rules govern every E2E test in `tests/e2e/`. They constrain generated output so
tests are stable by default. Reference implementation: `seed.spec.ts`.

## Rules (Playwright)

- Use `getByRole`, `getByLabel`, `getByText` as primary locators.
  Fall back to `getByPlaceholder` or `getByTestId` only when no accessible name exists.
  Never use CSS selectors, XPath, or DOM structure.
- Each test must be independently runnable — no shared state, no assumed prior tests.
  Own setup, action, assertion, and cleanup in one block.
- Never use `page.waitForTimeout()`. Wait for specific conditions:
  `toBeVisible()`, `waitForURL()`, `waitForResponse()`, `toHaveURL()`.
- Assert the business outcome, not implementation details.
- Use unique identifiers (`Date.now()` suffix) for test data to avoid collisions.
  Clean up created data within the same test.
- `storageState` is standard for authenticated tests. Exception: when the auth flow
  itself is the risk under test (e.g., prod-build-smoke), login through UI is correct.

## Why these rules

- `getByRole` traces to Playwright Best Practices: CSS selectors couple tests to
  implementation details and break on layout changes unrelated to the risk.
- Test independence is required because Playwright runs in parallel, in random order.
- `waitForTimeout` is officially an anti-pattern ("Tests that wait for time are
  inherently flaky").
- Assertions must fail when the risk materializes — decorative assertions that always
  pass regardless of the failure scenario provide false confidence.

## FinanceHQ-specific notes

- Password fields on `/login` and `/register` have no accessible name (spartan-ng
  label in sibling `div`, not `<label for>`). Use `getByRole('textbox').nth(1)` for
  password and `.nth(2)` for confirm-password. Revise if `aria-label` is added.
- Amount and payment-day spinbuttons use `getByPlaceholder('0.00')` and
  `getByPlaceholder('1–')` respectively. Same caveat.
- The prod build smoke test MUST run against the Spring Boot JAR on port 8080,
  not the Angular dev server on port 4200. The risk is the prod build, not ng serve.
