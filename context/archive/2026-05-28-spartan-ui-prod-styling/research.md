---
date: 2026-05-28T13:52:09Z
researcher: bkarnecki
git_commit: 72a55e8af32889df3cdbdcfb6f3b43407edc9f21
branch: master
repository: FinanceHQ
topic: "spartan.ui styling works locally but is completely broken in production"
tags: [research, frontend, tailwind, spartan-ui, angular, spring-security]
status: complete
last_updated: 2026-05-28
last_updated_by: bkarnecki
---

# Research: spartan.ui styling works locally but is completely broken in production

**Date**: 2026-05-28T13:52:09Z  
**Git Commit**: 72a55e8af32889df3cdbdcfb6f3b43407edc9f21  
**Branch**: master  
**Repository**: FinanceHQ

## Research Question

After switching from Angular Material to spartan.ui, the app looks correct locally but in the prod environment — even though logs are clean and the build succeeds — no styling is applied at all.

## Summary

Three independent root causes were identified. The **primary cause** is that `tailwind.config.js` does not import or apply the `@spartan-ng/brain/hlm-tailwind-preset`, causing Tailwind's production JIT compilation to omit all spartan-specific CSS variables, animation keyframes, and extended theme tokens. A secondary cause is the missing `tailwindcss-animate` package (required by the preset) and a missing `@import` in `styles.scss`. A third (suspected) cause — Spring Security not matching hashed CSS filenames — is likely a **false alarm** (see analysis below) but should be verified.

---

## Detailed Findings

### 1. CRITICAL — `tailwind.config.js` missing spartan.ui preset

**File:** `src/main/frontend/tailwind.config.js`

Current state:
```javascript
module.exports = {
  darkMode: 'class',
  content: ['./src/**/*.{html,ts}'],
  theme: { extend: {} },
  plugins: [],
};
```

The package `@spartan-ng/brain` (version `0.0.1-alpha.699`) is installed and its preset is at:
`node_modules/@spartan-ng/brain/hlm-tailwind-preset.js`

But the preset is **never imported or used**. This preset provides:
- CSS custom properties (design tokens: `--primary`, `--background`, `--foreground`, etc.)
- Extended theme colors that spartan components reference
- Animation keyframes (accordion open/close, fade, slide)
- The `tailwindcss-animate` plugin registration

**Why this breaks prod but not local:**  
`ng serve` (development) uses Tailwind in watch mode with no purging — every Tailwind utility class is generated, masking the missing preset. `ng build` (production) runs Tailwind's JIT scanner, which only emits the CSS it knows about from `tailwind.config.js`. Without the preset, all spartan design tokens and animations are absent from the production CSS bundle.

**Fix:**
```javascript
const { createGlobPatternsForDependencies } = require('@angular/compiler');
const { join } = require('path');

/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  presets: [require('@spartan-ng/brain/hlm-tailwind-preset')],
  content: [
    './src/**/*.{html,ts}',
    ...createGlobPatternsForDependencies(__dirname),
  ],
  theme: { extend: {} },
  plugins: [],
};
```

### 2. HIGH — `tailwindcss-animate` not installed

**File:** `src/main/frontend/package.json` (devDependencies section)

The package `tailwindcss-animate` is **absent** from `package.json` but is a hard dependency of `@spartan-ng/brain/hlm-tailwind-preset`. Without it, applying the preset will throw at build time or silently skip animation registration.

**Fix:** `npm install -D tailwindcss-animate` (inside `src/main/frontend/`)

### 3. HIGH — Missing spartan preset CSS import in `styles.scss`

**File:** `src/main/frontend/src/styles.scss`

Current content:
```scss
@tailwind base;
@tailwind components;
@tailwind utilities;
```

Missing:
```scss
@import '@spartan-ng/brain/hlm-tailwind-preset.css';
```

This import provides CSS custom property declarations (the actual values for the design tokens). Without it, components render with undefined CSS variables, resulting in transparent/invisible elements.

**Fix:** Add the import **before** the Tailwind directives:
```scss
@import '@spartan-ng/brain/hlm-tailwind-preset.css';

@tailwind base;
@tailwind components;
@tailwind utilities;
```

### 4. LOW (likely false alarm) — Spring Security pattern vs. hashed filenames

**File:** `src/main/java/com/example/finance_hq/security/SecurityConfig.java:48-49`

```java
.requestMatchers(HttpMethod.GET, "/*.js", "/*.js.map", "/*.css", "/*.css.map",
    "/*.ico", "/*.png", "/*.svg", "/*.woff", "/*.woff2", "/*.ttf", "/assets/**").permitAll()
```

Angular production builds output hashed filenames (configured in `angular.json:58` — `"outputHashing": "all"`):
- `styles-P5S7CVJJ.css`
- `main-XBJR72NA.js`

**Assessment:** The pattern `/*.css` in Spring Security's `PathPatternParser` (used in Spring Security 6+) uses `*` as a single-segment wildcard that matches any characters except `/`. Since hashed filenames like `styles-P5S7CVJJ.css` have no `/` in them, `/*.css` **should** match them. This is likely NOT blocking CSS delivery.

However, if you ever move to serving chunks from a subdirectory, the pattern would need to become `/**/*.css`. Worth testing in prod with browser DevTools → Network tab to confirm CSS files return 200 vs. 401/403.

---

## Code References

- `src/main/frontend/tailwind.config.js` — missing preset import (entire file, 7 lines)
- `src/main/frontend/src/styles.scss` — missing `@import` for preset CSS
- `src/main/frontend/package.json:22` — `@spartan-ng/brain` present; `tailwindcss-animate` absent
- `src/main/frontend/angular.json:58` — `"outputHashing": "all"` (causes hashed CSS filenames)
- `src/main/frontend/angular.json:72` — default configuration is `production`
- `src/main/java/com/example/finance_hq/security/SecurityConfig.java:48-49` — static asset permit rules
- `pom.xml:167` — Maven runs `npm run build` with `NODE_ENV=production`

---

## Architecture Insights

- **ng serve vs ng build divergence:** The local dev server (`ng serve`) intentionally includes all CSS to enable fast iteration. Production (`ng build`) purges unused CSS via Tailwind JIT scanning. This makes missing preset configuration invisible locally but fatal in prod.
- **The Maven pipeline is correct:** `npm run build` → `ng build` (production config) → output to `target/classes/static/`. The Dockerfile mirrors this. The problem is purely in the Angular/Tailwind config, not the build pipeline.
- **`createGlobPatternsForDependencies`** is recommended when using spartan.ui to ensure Tailwind scans the library's node_modules component templates, not just your own `src/`.

---

## Open Questions

1. Does `@spartan-ng/brain/hlm-tailwind-preset.css` export a CSS file, or is it only a JS preset? Verify the exact import path from the installed package.
2. After applying fixes, run `ng build` locally and inspect `target/classes/static/styles-*.css` to confirm design tokens are present before pushing to Railway.
