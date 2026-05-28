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
| Styling | Tailwind CSS v3 (pin `tailwindcss@^3` — v4 has a breaking config format) |
| Icons | Lucide Angular |
| Remove | `@angular/material`, `@angular/cdk` |

### 1.1 Compatibility check (do this first)

spartan/ui targets Angular 17+. Before installing, verify Angular 21 compatibility:

```bash
# In src/main/frontend/
npx ng add @spartan-ng/cli
# If peer-dep errors appear for Angular 21, install with --legacy-peer-deps and verify at runtime
```

Exact packages needed:
```bash
npm install @spartan-ng/ui-button-helm @spartan-ng/ui-input-helm @spartan-ng/ui-label-helm \
  @spartan-ng/ui-card-helm @spartan-ng/ui-dialog-helm @spartan-ng/ui-table-helm \
  @spartan-ng/ui-badge-helm @spartan-ng/ui-select-helm @spartan-ng/ui-formfield-helm \
  @spartan-ng/ui-icon-helm lucide-angular tailwindcss@^3 autoprefixer postcss
```

**Fallback:** If spartan/ui HLM packages are incompatible with Angular 21, implement the same visual design using raw Tailwind utility classes + `@ng-primitives` for accessible headless primitives. The visual spec (colors, layout, badges) remains unchanged; only the component layer differs.

---

## 2. Migration Approach

**Clean Slate.** Keep `core/auth/` untouched (AuthService, TokenStorageService, AuthInterceptor, AuthGuard, all routes and providers). Delete all Angular Material imports, directives, and component templates. Rebuild every template fresh with spartan/ui HLM components.

**Keep:**
- `src/main/frontend/src/app/core/auth/` — all services, guard, interceptor
- `src/main/frontend/src/app/app.routes.ts`
- `src/main/frontend/src/environments/`

**Delete/replace:**
- All `@angular/material` and `@angular/cdk` imports across the codebase
- All component `.html` templates and `.scss` styles
- Root `mat-toolbar` in `app.html` — replace with bare `<router-outlet />`
- `@angular/material` and `@angular/cdk` from `package.json`
- `MatSnackBar` injection + snack logic in `register.component.ts` (replace with inline alert)
- `ngOnDestroy` + `formChangeSub` subscription in `register.component.ts` (only existed to dismiss snack)

**Update:**
- `app.config.ts` — remove `provideAnimationsAsync()` import and call (not needed without Angular Material)
- `app.ts` — remove `AuthService` injection and `signOut()` method (logout moves to `SidebarComponent`)

**Add:**
- `@spartan-ng/ui-*-helm` packages (see section 1.1)
- Tailwind CSS v3 + `tailwind.config.js`
- `lucide-angular`
- `src/main/frontend/src/app/shared/` folder structure (see section 6.1)

**App component after migration:**
`app.html` becomes a bare `<router-outlet />`. `app.ts` becomes an empty shell with no injected services. The sidebar (with logout) lives inside a `SidebarComponent` rendered by the Obligations page layout — auth pages render without any sidebar.

---

## 3. Obligation Data Model

The obligations feature requires a shared TypeScript interface and agreed API endpoints. Both the Angular service layer and the Spring Boot backend must use this contract.

### TypeScript interface

```typescript
// features/obligations/obligation.model.ts
export interface Obligation {
  id: string;
  name: string;
  amount: number;
  category: 'TOP' | 'HIGH' | 'LOW';
  period: 'RECURRING' | 'FIXED_TERM';
  paymentDay: number;   // 1–31, day of month payment is due
  createdAt: string;    // ISO-8601
}

export type CreateObligationDto = Omit<Obligation, 'id' | 'createdAt'>;
export type UpdateObligationDto = Pick<Obligation, 'amount' | 'paymentDay'>;
```

### API endpoints

| Method | Path | Request body | Response |
|---|---|---|---|
| `GET` | `/api/obligations` | — | `Obligation[]` |
| `POST` | `/api/obligations` | `CreateObligationDto` | `Obligation` |
| `PATCH` | `/api/obligations/{id}` | `UpdateObligationDto` | `Obligation` |
| `DELETE` | `/api/obligations/{id}` | — | `204 No Content` |

All endpoints are authenticated (Bearer token via `AuthInterceptor`). The backend must implement these before the obligations page can be wired — stub with in-memory data during frontend development if needed.

---

## 4. Visual Design System

### Color Modes
- **Default:** Light (zinc/white palette)
- **Dark mode:** Supported via Tailwind `dark:` variants, toggled by user, persisted in `localStorage`
- **Toggle placement:** Bottom of sidebar, above Sign out

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
| Text subtle | `#52525b` | Column headers (zinc-600 reads on both backgrounds — intentional) |

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

## 5. App Shell

### Layout
- `app.html` is a bare `<router-outlet />` — no sidebar, no toolbar at root level
- Auth pages (`/login`, `/register`) render their own centered-card layout, no sidebar
- Protected routes render inside a `LayoutComponent` (`shared/layout/layout.component.ts`) which composes the sidebar + `<router-outlet />` for page content. `LayoutComponent` is the parent route in `app.routes.ts` with `canActivate: [AuthGuard]`; child routes (obligations, future pages) nest under it.

```typescript
// app.routes.ts structure
{ path: 'login', loadComponent: () => LoginComponent },
{ path: 'register', loadComponent: () => RegisterComponent },
{
  path: '',
  component: LayoutComponent,   // sidebar shell
  canActivate: [AuthGuard],
  children: [
    { path: 'dashboard', loadComponent: () => ObligationsComponent },
    // future pages added here
  ]
},
{ path: '**', loadComponent: () => NotFoundComponent },
```

### SidebarComponent (`shared/layout/sidebar/`)
Standalone component, injected into the protected layout. Contents top to bottom:
1. **Wordmark** — "FinanceHQ" `font-bold text-lg`
2. **Section label** — "MENU" uppercase muted
3. **Nav link** — Obligations (active state: `bg-zinc-100 dark:bg-zinc-800 font-semibold`)
4. **Bottom area** — Dark mode toggle + Sign out button (calls `authService.logout()` then navigates to `/login`)

Width: `w-[170px]`, fixed, `border-r border-zinc-200 dark:border-zinc-800`, `bg-zinc-50 dark:bg-zinc-950`.

### Future sidebar extension
When Dashboard and other pages are added, insert nav links between the section label and the bottom area. Each link follows the same active/inactive class pattern. `SidebarComponent` owns the nav links array — no changes needed in page components.

---

## 6. Pages

### 6.1 Login (`/login`)

**Layout:** `bg-zinc-100 dark:bg-zinc-900` full-screen, centered card (`w-80 bg-white dark:bg-zinc-950 border rounded-xl p-6 shadow-sm`)

**Fields:**
- Email — `hlm-input`, type=email, required
- Password — `hlm-input`, type=password, with show/hide toggle (Lucide `Eye`/`EyeOff`)

**Submit button:** Full-width, `hlmBtn`, shows spinner + disabled during submission.

**Error handling:** Inline alert below the form (red tinted `bg-red-50 dark:bg-red-950 border border-red-200`) for 401 (invalid credentials) or 500 (server error). No snack bars.

**Footer link:** "Don't have an account? Register" → `/register`

---

### 6.2 Register (`/register`)

**Layout:** Same card shell as Login.

**Fields:**
- Email — `hlm-input`, type=email, required
- Password — `hlm-input`, type=password, with show/hide toggle.
  - On focus, show inline hint list below the field. Each item turns green when its validator passes:
    ```html
    <ul class="mt-2 space-y-1 text-xs">
      <li [class.text-green-500]="!form.controls.password.hasError('pwLength')"
          [class.text-zinc-400]="form.controls.password.hasError('pwLength')">
        ≥ 8 characters
      </li>
      <!-- repeat for pwUppercase, pwDigit, pwSpecial -->
    </ul>
    ```
  - Uses existing validator error keys: `pwLength`, `pwUppercase`, `pwDigit`, `pwSpecial`
- Confirm password — `hlm-input`, type=password. Shows `"Passwords do not match"` inline when `passwordMismatch` error is present and field is touched.

**Submit button:** Full-width, disabled until form valid.

**Error handling:**
- Inline field errors (red border + `<p class="text-xs text-red-500 mt-1">`) for validation failures
- 409 (email taken) — inline alert below the form (same style as Login error)
- No snack bars, no `MatSnackBar`, no `ngOnDestroy`

**Footer link:** "Already have an account? Sign in" → `/login`

---

### 6.3 Obligations (`/dashboard`, via `ObligationsComponent`)

**Routing note:** The `/dashboard` route loads `ObligationsComponent` directly (from `features/obligations/`). There is no separate `DashboardComponent` or `features/dashboard/` folder in MVP — the obligations list *is* the dashboard for now. A separate dashboard page is a future concern (see section 8).

**Page header:**
- Left: "Obligations" `text-xl font-bold` + subtitle "N active obligations" where N = total count returned by `GET /api/obligations` (all obligations, no status filtering)
- Right: "+ Add Obligation" button (`hlmBtn` dark fill)

**Table (`hlm-table`):**

| Column | Width | Notes |
|---|---|---|
| NAME | `2fr` | Primary text bold + subtitle muted (period type: "Recurring monthly" or "Fixed term") |
| AMOUNT | `1fr` | `font-semibold`, formatted as `$1,200` |
| DUE DAY | `1fr` | Ordinal display: 1st, 2nd, 3rd, 15th, 22nd |
| CATEGORY | `1fr` | `CategoryBadgeComponent` |
| ACTIONS | `64px` | Edit icon (pencil) + Delete icon (trash) |

- **Empty state:** Centered message "No obligations yet. Add your first one." with "+ Add Obligation" button
- **Edit action:** Opens same modal pre-filled; only Amount and Payment Day are editable (per FR-005). Name, Category, and Period are shown read-only.
- **Delete action:** Opens `hlmDialog` confirmation: "Delete obligation?" with Cancel + Delete (destructive red) buttons (per FR-006). Calls `DELETE /api/obligations/{id}` on confirm.

**Add/Edit Obligation Modal (`hlmDialog`):**

Fields (2-column grid):

| Field | Type | Validation | Add only? |
|---|---|---|---|
| Name | text input | required, maxLength 100 | Yes — read-only in edit |
| Amount | number input | required, min 0.01, max 999999.99 | No |
| Category | select (TOP/HIGH/LOW) | required | Yes — read-only in edit |
| Period | select (Recurring/Fixed term) | required | Yes — read-only in edit |
| Payment day | number input | required, integer, min 1, max 31 | No |

Footer: Cancel (outline) + Save (filled) buttons, right-aligned. Save disabled while form invalid. Shows spinner on Save during submission.

---

### 6.4 Not Found (`/**`)

Simple centered message: "404 — Page not found" with a link back to `/dashboard`. Same centered-card shell as auth pages (no sidebar, no auth required).

---

## 7. Frontend Implementation Guidelines

These rules apply to all future frontend work on this project.

### 7.1 Folder structure

```
src/main/frontend/src/app/
├── core/
│   └── auth/                  # keep as-is
├── features/
│   ├── login/
│   ├── register/
│   ├── obligations/           # serves the /dashboard route in MVP
│   │   ├── obligations.component.ts
│   │   ├── obligations.component.html
│   │   ├── obligation.model.ts       # Obligation interface + DTOs
│   │   └── obligations.service.ts    # HTTP calls
│   └── not-found/
└── shared/
    ├── layout/
    │   ├── layout.component.ts        # LayoutComponent — sidebar shell for protected routes
    │   └── sidebar/                   # SidebarComponent (nav + logout + dark toggle)
    ├── theme.service.ts        # ThemeService — single source of truth for dark mode
    └── ui/
        └── category-badge/    # CategoryBadgeComponent
```

Note: `features/dashboard/` is not created in MVP. A future dashboard page will be added to `features/dashboard/` and wired as a child route of `LayoutComponent` at that time.

### 7.2 Component conventions

- **Always use HLM directives** for UI elements — never raw HTML buttons, inputs, or selects where an HLM equivalent exists.
- **Standalone components only** — no NgModules.
- **One component per file.** If a file grows past ~150 lines, extract a child component.

### 7.3 Tailwind usage

- Use Tailwind utility classes directly in templates — no custom SCSS unless absolutely necessary.
- Dark mode via `dark:` variants on every color token — never hardcode colors in component styles.
- Pin `tailwindcss@^3` — v4 uses a different config format that breaks spartan/ui setup.
- Spacing scale: stick to Tailwind defaults — no arbitrary values unless unavoidable.

### 7.4 Dark mode pattern

- Apply `dark` class to `<html>` element (Tailwind `darkMode: 'class'` in `tailwind.config.js`).
- `ThemeService` (`shared/theme.service.ts`) is the single source of truth:
  - On init: reads `'theme'` from `localStorage`, applies/removes `dark` class on `document.documentElement`
  - `toggle()`: flips class and persists to `localStorage`
- **Initialization:** `ThemeService` must be injected in `app.ts` (the root `App` component) so the `dark` class is applied before any route renders — including auth pages. Do not rely solely on `SidebarComponent` injection, which would cause a flash of light mode on page load for dark-mode users.
- Child components inject `ThemeService` and bind the toggle button — never manipulate `document` directly in components.

### 7.5 Form validation pattern

- Use Angular Reactive Forms throughout — no template-driven forms.
- Display errors only after the field is touched or form submitted.
- Error message: `<p class="text-xs text-red-500 mt-1">Message here</p>` directly below the input.
- No snack bars or toasts for form errors — errors stay co-located with their fields.

### 7.6 Extending the sidebar

When adding a new page:
1. Add the route to `app.routes.ts` (lazy-loaded, `AuthGuard` if protected)
2. Add a nav link entry to `SidebarComponent` — follow the active/inactive class pattern
3. Create `features/<page-name>/` with its component and service

### 7.7 Badge system

`CategoryBadgeComponent` (`shared/ui/category-badge/`) accepts `@Input() category: 'TOP' | 'HIGH' | 'LOW'` and applies the correct light/dark color tokens from the design system table (section 4). Never inline badge colors in feature components.

### 7.8 API communication

- HTTP calls live in `features/<name>/<name>.service.ts` (or `core/` for cross-cutting concerns)
- `AuthInterceptor` attaches Bearer tokens automatically — components never touch headers
- Error handling: catch in the service, surface a typed error object to the component, display inline

---

## 8. Out of Scope (future)

| Feature | Notes |
|---|---|
| Dashboard page | Route exists, shows "Coming soon" — no obligation UI there |
| Income tracking | Not in MVP |
| Fund management | Not in MVP |
| Mobile sidebar | Collapses to icon-only below `md:` — deferred |
| SMS/push notifications | Email only in v0.1 (backend concern) |
