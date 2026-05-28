# Frontend Rebuild — spartan/ui Design Spec

**Date:** 2026-05-28
**Scope:** MVP — Register, Login, Obligations (list + add + edit + delete)
**Out of scope:** Dashboard analytics, income tracking, fund management

---

## 1. Stack

| Concern | Choice |
|---|---|
| Framework | Angular 21 (standalone components) |
| UI components | spartan/ui HLM (`@spartan-ng/ui-*-helm`) |
| Styling | Tailwind CSS v3 |
| Icons | Lucide Angular |
| Remove | `@angular/material`, `@angular/cdk` |

---

## 2. Migration Approach

**Clean Slate.** Keep `core/auth/` untouched (AuthService, TokenStorageService, AuthInterceptor, AuthGuard, all routes and providers). Delete all Angular Material imports, directives, and component templates. Rebuild every template fresh with spartan/ui HLM components.

**Keep:**
- `src/main/frontend/src/app/core/auth/` — all services, guard, interceptor
- `src/main/frontend/src/app/app.routes.ts`
- `src/main/frontend/src/app/app.config.ts`
- `src/main/frontend/src/environments/`

**Delete/replace:**
- All `@angular/material` and `@angular/cdk` imports across the codebase
- All component `.html` templates and `.scss` styles
- Root toolbar in `app.html`
- `@angular/material` and `@angular/cdk` from `package.json`

**Add:**
- `@spartan-ng/ui-*-helm` packages (button, input, label, card, dialog, table, badge, select, form-field)
- Tailwind CSS v3 + `tailwind.config.js`
- `lucide-angular`
- `src/main/frontend/src/app/shared/ui/` folder for any custom HLM wrappers

---

## 3. Visual Design System

### Color Modes
- **Default:** Light (zinc/white palette)
- **Dark mode:** Supported via Tailwind `dark:` variants, toggled by user, persisted in `localStorage`
- **Toggle placement:** Top-right of sidebar

### Light Mode Palette
| Token | Value | Usage |
|---|---|---|
| Background | `#ffffff` | Main content area |
| Surface | `#fafafa` | Sidebar, table header |
| Border | `#e5e7eb` | Cards, inputs, table rows |
| Text primary | `#18181b` | Headings, values |
| Text muted | `#71717a` | Subtitles, placeholders |
| Text subtle | `#52525b` | Column headers |

### Dark Mode Palette
| Token | Value | Usage |
|---|---|---|
| Background | `#09090b` | Main content area |
| Surface | `#111113` | Sidebar |
| Border | `#27272a` | Cards, inputs, table rows |
| Text primary | `#fafafa` | Headings, values |
| Text muted | `#71717a` | Subtitles, placeholders |
| Text subtle | `#52525b` (same) | Column headers |

### Category Badge System
| Category | Light bg | Light text | Light border | Dark bg | Dark text | Dark border |
|---|---|---|---|---|---|---|
| TOP | `#fef2f2` | `#dc2626` | `#fecaca` | `#3f1219` | `#f87171` | `#7f1d1d` |
| HIGH | `#fffbeb` | `#d97706` | `#fde68a` | `#422006` | `#fb923c` | `#7c2d12` |
| LOW | `#f0fdf4` | `#16a34a` | `#bbf7d0` | `#052e16` | `#4ade80` | `#14532d` |

### Typography
- Font: System UI stack (Tailwind default) — no custom font imports
- Headings: `font-bold text-zinc-900 dark:text-zinc-50`
- Body: `text-sm text-zinc-700 dark:text-zinc-300`
- Muted: `text-xs text-zinc-500`

---

## 4. App Shell

### Layout
- Fixed sidebar (`w-[170px]`) + flex-1 main content area
- Full-height layout (`min-h-screen flex`)
- Sidebar background: `bg-zinc-50 dark:bg-zinc-950 border-r border-zinc-200 dark:border-zinc-800`

### Sidebar Contents (top to bottom)
1. **Wordmark** — "FinanceHQ" `font-bold text-lg`
2. **Section label** — "MENU" uppercase muted
3. **Nav link** — Obligations (active state: `bg-zinc-100 dark:bg-zinc-800 font-semibold`)
4. **Bottom area** — Dark mode toggle + Sign out link

### Future sidebar extension
When Dashboard and other pages are added, insert nav links between the section label and the bottom area. Each link follows the same active/inactive pattern.

---

## 5. Pages

### 5.1 Login (`/login`)

**Layout:** `bg-zinc-100 dark:bg-zinc-900` full-screen, centered card (`w-80 bg-white dark:bg-zinc-950 border rounded-xl p-6 shadow-sm`)

**Fields:**
- Email — `hlm-input`, type=email, required
- Password — `hlm-input`, type=password, with show/hide toggle (Lucide `Eye`/`EyeOff`)

**Submit button:** Full-width, `hlmBtn`, shows spinner + disabled during submission.

**Error handling:** Inline alert below the form (red tinted) for 401 (invalid credentials) or 500 (server error). No snack bars.

**Footer link:** "Don't have an account? Register" → `/register`

---

### 5.2 Register (`/register`)

**Layout:** Same card shell as Login.

**Fields:**
- Email — `hlm-input`, type=email, required
- Password — `hlm-input`, type=password, with show/hide toggle. On focus, show hint list below: ≥8 chars, 1 uppercase, 1 digit, 1 special character (each item goes green when satisfied)
- Confirm password — `hlm-input`, type=password. Shows mismatch error inline

**Submit button:** Full-width, disabled until form valid.

**Error handling:**
- Inline field errors (red border + message) for validation failures
- 409 (email taken) — inline alert below the form
- No snack bars

**Footer link:** "Already have an account? Sign in" → `/login`

---

### 5.3 Obligations (`/dashboard`, protected by AuthGuard)

**Page header:**
- Left: "Obligations" `text-xl font-bold` + subtitle "N active obligations"
- Right: "+ Add Obligation" button (`hlmBtn` dark fill)

**Table (`hlm-table`):**

| Column | Width | Notes |
|---|---|---|
| NAME | `2fr` | Primary text bold + subtitle muted (period type) |
| AMOUNT | `1fr` | `font-semibold` |
| DUE DAY | `1fr` | Ordinal day (1st, 15th, 22nd) |
| CATEGORY | `1fr` | Badge component |
| ACTIONS | `64px` | Edit icon + Delete icon buttons |

- **Empty state:** Centered message "No obligations yet. Add your first one." with "+ Add Obligation" button
- **Edit action:** Opens same modal pre-filled (amount + payment day only editable, per FR-005)
- **Delete action:** Opens confirmation dialog before deleting (per FR-006)

**Add/Edit Obligation Modal (`hlmDialog`):**

Fields (2-column grid):
- Name (add only, not editable) — text input
- Amount — number input
- Category (add only) — select: TOP / HIGH / LOW
- Period (add only) — select: Recurring / Fixed term
- Payment day of month — number input (1–31)

Footer: Cancel (outline) + Save (filled) buttons, right-aligned.

---

### 5.4 Not Found (`/**`)

Simple centered message: "404 — Page not found" with a link back to `/dashboard`. Same shell as auth pages (no sidebar, no auth required).

---

## 6. Frontend Implementation Guidelines

These rules apply to all future frontend work on this project.

### 6.1 Component conventions

- **Always use HLM directives** for UI elements — never raw HTML buttons, inputs, or selects where an HLM equivalent exists.
- **Standalone components only** — no NgModules.
- **One component per file.** If a file grows past ~150 lines, extract a child component.
- **Feature folders** under `features/` for each page. Shared presentational components go in `shared/ui/`.

### 6.2 Tailwind usage

- Use Tailwind utility classes directly in templates — no custom SCSS unless absolutely necessary.
- Dark mode via `dark:` variants on every color token — never hardcode colors in component styles.
- Spacing scale: stick to Tailwind defaults (`p-4`, `gap-3`, `rounded-lg`, etc.) — no arbitrary values unless unavoidable.
- Responsive: mobile-first. Sidebar collapses to icon-only below `md:` breakpoint (future work).

### 6.3 Dark mode pattern

```typescript
// ThemeService (shared/theme.service.ts)
// On init: read 'theme' from localStorage, apply 'dark' class to <html>
// Toggle: flip class + persist to localStorage
// Components: inject ThemeService, bind toggle button to theme.toggle()
```

- Apply `dark` class to `<html>` element (Tailwind `darkMode: 'class'` strategy).
- ThemeService is the single source of truth — no direct `document` manipulation in components.

### 6.4 Form validation pattern

- Use Angular Reactive Forms throughout — no template-driven forms.
- Display errors only after the field is touched or form is submitted.
- Error message format: `<p class="text-xs text-red-500 mt-1">Message</p>` below the input.
- No snack bars / toasts for validation errors — keep errors co-located with their fields.

### 6.5 Extending the sidebar

When adding a new page:
1. Add the route to `app.routes.ts` (lazy-loaded, protected by `AuthGuard` if needed)
2. Add a nav link to the sidebar component — follow the active/inactive class pattern
3. Create a new `features/<page-name>/` folder with its component

### 6.6 Badge system

Import the category badge as a shared component `shared/ui/category-badge/`. It accepts `category: 'TOP' | 'HIGH' | 'LOW'` and applies the correct light/dark color tokens from the design system table above. Never inline badge colors in feature components.

### 6.7 API communication

- All HTTP calls go through Angular services in `core/` or `features/<name>/<name>.service.ts`
- `AuthInterceptor` handles token attachment automatically — components never touch headers
- Error handling: catch in the service, surface a typed error to the component, display inline

---

## 7. Out of Scope (future)

| Feature | Notes |
|---|---|
| Dashboard page | Route placeholder only — "Coming soon" message |
| Income tracking | Not in MVP |
| Fund management | Not in MVP |
| Mobile sidebar | Collapses to icon-only below `md:` — deferred |
| SMS/push notifications | Email only in v0.1 |
