# Frontend Rebuild — spartan/ui Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Angular Material with spartan/ui HLM + Tailwind CSS, rebuilding all page templates while keeping `core/auth/` intact, and delivering a clean light/dark-mode frontend for Login, Register, and the Obligations page.

**Architecture:** `app.html` becomes a bare `<router-outlet>`. Protected routes nest under a `LayoutComponent` (sidebar shell). Auth pages are standalone full-screen cards. All UI components use spartan/ui HLM directives on top of Tailwind utility classes. A `ThemeService` owns the dark-mode `dark` class on `<html>`.

**Tech Stack:** Angular 21, spartan/ui HLM (`@spartan-ng/brain-*`), Tailwind CSS v3, Lucide Angular, Angular Reactive Forms

**Spec:** `docs/superpowers/specs/2026-05-28-frontend-spartan-ui-design.md`

**All commands run from:** `src/main/frontend/`

---

## File Map

### New files
| File | Responsibility |
|---|---|
| `tailwind.config.js` | Tailwind v3 config — content paths, dark mode class strategy |
| `postcss.config.js` | PostCSS config for Tailwind |
| `src/app/shared/theme.service.ts` | Dark mode toggle — owns `dark` class on `<html>`, persists to `localStorage` |
| `src/app/shared/theme.service.spec.ts` | Unit tests for ThemeService |
| `src/app/shared/layout/layout.component.ts` | Shell wrapping sidebar + `<router-outlet>` for all protected routes |
| `src/app/shared/layout/layout.component.html` | Layout template |
| `src/app/shared/layout/sidebar/sidebar.component.ts` | Sidebar — nav links, dark toggle, sign out |
| `src/app/shared/layout/sidebar/sidebar.component.html` | Sidebar template |
| `src/app/shared/ui/category-badge/category-badge.component.ts` | Badge for TOP/HIGH/LOW with light/dark color tokens |
| `src/app/shared/ui/category-badge/category-badge.component.html` | Badge template |
| `src/app/features/obligations/obligation.model.ts` | `Obligation` interface + `CreateObligationDto` + `UpdateObligationDto` |
| `src/app/features/obligations/obligations.service.ts` | HTTP calls: GET/POST/PATCH/DELETE `/api/obligations` |
| `src/app/features/obligations/obligations.service.spec.ts` | Unit tests for ObligationsService |
| `src/app/features/obligations/obligations.component.ts` | Obligations page — list, empty state, open add/edit/delete dialogs |
| `src/app/features/obligations/obligations.component.html` | Obligations table template |

### Modified files
| File | What changes |
|---|---|
| `package.json` | Remove `@angular/material`, `@angular/cdk`. Add `@spartan-ng/brain-*`, `lucide-angular`, `tailwindcss@^3`, `autoprefixer`, `postcss` |
| `src/styles.scss` | Replace Material theme with Tailwind directives |
| `src/app/app.config.ts` | Remove `provideAnimationsAsync()` |
| `src/app/app.ts` | Remove Material imports + `signOut()`. Inject `ThemeService` for init. |
| `src/app/app.html` | Bare `<router-outlet />` |
| `src/app/app.scss` | Clear all styles |
| `src/app/app.routes.ts` | Replace flat routes with `LayoutComponent` parent + nested `dashboard` child |
| `src/app/features/login/login.component.ts` | Rebuild with Reactive Forms + spartan/ui |
| `src/app/features/login/login.component.html` | Centered card, spartan/ui inputs |
| `src/app/features/login/login.component.scss` | Clear — Tailwind only |
| `src/app/features/register/register.component.ts` | Remove MatSnackBar/ngOnDestroy. Rebuild with spartan/ui |
| `src/app/features/register/register.component.html` | Card with password hint list |
| `src/app/features/register/register.component.scss` | Clear — Tailwind only |
| `src/app/features/not-found/not-found.component.html` | Minimal 404, Tailwind classes |
| `src/app/features/not-found/not-found.component.scss` | Clear |

### Deleted files
| File | Reason |
|---|---|
| `src/app/features/dashboard/dashboard.component.*` | Replaced by `ObligationsComponent` at `/dashboard` route |

---

## Task 1: Compatibility Check + Install Tailwind

**Files:**
- Create: `tailwind.config.js`
- Create: `postcss.config.js`
- Modify: `src/styles.scss`

- [ ] **Step 1.1: Install Tailwind v3**

```bash
npm install -D tailwindcss@^3 postcss autoprefixer
npx tailwindcss init -p
```

Expected: `tailwind.config.js` and `postcss.config.js` created.

- [ ] **Step 1.2: Configure Tailwind**

Replace the contents of `tailwind.config.js`:

```js
/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: ['./src/**/*.{html,ts}'],
  theme: { extend: {} },
  plugins: [],
};
```

- [ ] **Step 1.3: Add Tailwind to global styles**

Replace `src/styles.scss` entirely:

```scss
@tailwind base;
@tailwind components;
@tailwind utilities;
```

- [ ] **Step 1.4: Verify Tailwind compiles**

```bash
ng build --configuration development
```

Expected: Build succeeds with no errors.

- [ ] **Step 1.5: Commit**

```bash
git add tailwind.config.js postcss.config.js src/styles.scss package.json package-lock.json
git commit -m "feat(frontend): install Tailwind CSS v3"
```

---

## Task 2: Install spartan/ui Brain Primitives + Lucide

**Files:**
- Modify: `package.json`

- [ ] **Step 2.1: Attempt spartan/ui CLI install**

```bash
ng add @spartan-ng/cli
```

If this succeeds (no Angular 21 peer-dep conflict): the CLI is available, continue to Step 2.2.

If this fails with peer-dep errors: skip to Step 2.3 (manual install).

- [ ] **Step 2.2: Add spartan/ui HLM packages via CLI (if Step 2.1 succeeded)**

```bash
ng generate @spartan-ng/cli:ui button
ng generate @spartan-ng/cli:ui input
ng generate @spartan-ng/cli:ui label
ng generate @spartan-ng/cli:ui card
ng generate @spartan-ng/cli:ui dialog
ng generate @spartan-ng/cli:ui table
ng generate @spartan-ng/cli:ui badge
ng generate @spartan-ng/cli:ui select
```

Skip to Step 2.4.

- [ ] **Step 2.3: Manual install (fallback — if CLI failed)**

```bash
npm install @spartan-ng/brain-button @spartan-ng/brain-dialog \
  @spartan-ng/brain-select @spartan-ng/brain-core --legacy-peer-deps
```

For UI components (input, button, badge, table, etc.), we will build thin Tailwind wrappers in `shared/ui/hlm/`. These follow the same pattern spartan/ui generates: an Angular directive or component that applies Tailwind class variants. **All subsequent tasks referencing `hlmBtn`, `hlm-input`, etc. refer to these wrappers.**

- [ ] **Step 2.4: Install Lucide Angular**

```bash
npm install lucide-angular
```

- [ ] **Step 2.5: Verify build**

```bash
ng build --configuration development
```

Expected: Build succeeds.

- [ ] **Step 2.6: Commit**

```bash
git add package.json package-lock.json
git commit -m "feat(frontend): install spartan/ui primitives and Lucide Angular"
```

---

## Task 3: Remove Angular Material — App Shell Cleanup

**Files:**
- Modify: `package.json`
- Modify: `src/app/app.config.ts`
- Modify: `src/app/app.ts`
- Modify: `src/app/app.html`
- Modify: `src/app/app.scss`

- [ ] **Step 3.1: Remove Angular Material packages**

```bash
npm uninstall @angular/material @angular/cdk @angular/animations
```

- [ ] **Step 3.2: Update `app.config.ts`**

Replace the file:

```typescript
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor])),
  ],
};
```

- [ ] **Step 3.3: Update `app.ts`**

Replace the file:

```typescript
import { Component, inject, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './shared/theme.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  private theme = inject(ThemeService);

  ngOnInit(): void {
    this.theme.init();
  }
}
```

- [ ] **Step 3.4: Update `app.html`**

Replace the file:

```html
<router-outlet />
```

- [ ] **Step 3.5: Clear `app.scss`**

Replace the file:

```scss
// no global component styles — use Tailwind utilities in templates
```

- [ ] **Step 3.6: Verify build**

```bash
ng build --configuration development
```

Expected: Build succeeds. (ThemeService doesn't exist yet — if Angular errors on the inject, temporarily comment out lines 2 and 9–11 in `app.ts` until Task 4 creates it.)

- [ ] **Step 3.7: Commit**

```bash
git add src/app/app.config.ts src/app/app.ts src/app/app.html src/app/app.scss package.json package-lock.json
git commit -m "feat(frontend): remove Angular Material, strip app shell to bare router-outlet"
```

---

## Task 4: ThemeService

**Files:**
- Create: `src/app/shared/theme.service.ts`
- Create: `src/app/shared/theme.service.spec.ts`

- [ ] **Step 4.1: Write failing tests**

Create `src/app/shared/theme.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  let service: ThemeService;

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark');
    TestBed.configureTestingModule({});
    service = TestBed.inject(ThemeService);
  });

  it('should apply dark class when localStorage theme is dark', () => {
    localStorage.setItem('theme', 'dark');
    service.init();
    expect(document.documentElement.classList.contains('dark')).toBeTrue();
  });

  it('should not apply dark class when localStorage theme is light', () => {
    localStorage.setItem('theme', 'light');
    service.init();
    expect(document.documentElement.classList.contains('dark')).toBeFalse();
  });

  it('should not apply dark class when no theme in localStorage', () => {
    service.init();
    expect(document.documentElement.classList.contains('dark')).toBeFalse();
  });

  it('should toggle dark class on and persist to localStorage', () => {
    service.init();
    service.toggle();
    expect(document.documentElement.classList.contains('dark')).toBeTrue();
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  it('should toggle dark class off and persist to localStorage', () => {
    localStorage.setItem('theme', 'dark');
    service.init();
    service.toggle();
    expect(document.documentElement.classList.contains('dark')).toBeFalse();
    expect(localStorage.getItem('theme')).toBe('light');
  });

  it('should expose isDark signal reflecting current state', () => {
    localStorage.setItem('theme', 'dark');
    service.init();
    expect(service.isDark()).toBeTrue();
    service.toggle();
    expect(service.isDark()).toBeFalse();
  });
});
```

- [ ] **Step 4.2: Run tests to verify they fail**

```bash
ng test --include="**/theme.service.spec.ts" --watch=false
```

Expected: FAIL — `ThemeService` not found.

- [ ] **Step 4.3: Implement ThemeService**

Create `src/app/shared/theme.service.ts`:

```typescript
import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  isDark = signal(false);

  init(): void {
    const stored = localStorage.getItem('theme');
    const dark = stored === 'dark';
    this.isDark.set(dark);
    this.applyClass(dark);
  }

  toggle(): void {
    const next = !this.isDark();
    this.isDark.set(next);
    this.applyClass(next);
    localStorage.setItem('theme', next ? 'dark' : 'light');
  }

  private applyClass(dark: boolean): void {
    document.documentElement.classList.toggle('dark', dark);
  }
}
```

- [ ] **Step 4.4: Run tests to verify they pass**

```bash
ng test --include="**/theme.service.spec.ts" --watch=false
```

Expected: 6 tests PASS.

- [ ] **Step 4.5: Commit**

```bash
git add src/app/shared/theme.service.ts src/app/shared/theme.service.spec.ts
git commit -m "feat(frontend): add ThemeService with dark mode toggle"
```

---

## Task 5: Routing — LayoutComponent + Update Routes

**Files:**
- Create: `src/app/shared/layout/layout.component.ts`
- Create: `src/app/shared/layout/layout.component.html`
- Modify: `src/app/app.routes.ts`
- Delete: `src/app/features/dashboard/dashboard.component.*` (3 files)

- [ ] **Step 5.1: Create LayoutComponent**

Create `src/app/shared/layout/layout.component.ts`:

```typescript
import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SidebarComponent } from './sidebar/sidebar.component';

@Component({
  selector: 'app-layout',
  imports: [RouterOutlet, SidebarComponent],
  templateUrl: './layout.component.html',
})
export class LayoutComponent {}
```

Create `src/app/shared/layout/layout.component.html`:

```html
<div class="flex min-h-screen bg-white dark:bg-zinc-950">
  <app-sidebar />
  <main class="flex-1 p-6 overflow-auto">
    <router-outlet />
  </main>
</div>
```

- [ ] **Step 5.2: Update app.routes.ts**

Replace the file:

```typescript
import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'login',
    loadComponent: () =>
      import('./features/login/login.component').then(m => m.LoginComponent),
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./features/register/register.component').then(m => m.RegisterComponent),
  },
  {
    path: '',
    loadComponent: () =>
      import('./shared/layout/layout.component').then(m => m.LayoutComponent),
    canActivate: [authGuard],
    children: [
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./features/obligations/obligations.component').then(
            m => m.ObligationsComponent,
          ),
      },
    ],
  },
  {
    path: '**',
    loadComponent: () =>
      import('./features/not-found/not-found.component').then(m => m.NotFoundComponent),
  },
];
```

- [ ] **Step 5.3: Delete old dashboard component**

```bash
rm src/app/features/dashboard/dashboard.component.ts \
   src/app/features/dashboard/dashboard.component.html \
   src/app/features/dashboard/dashboard.component.scss
rmdir src/app/features/dashboard
```

- [ ] **Step 5.4: Create placeholder ObligationsComponent (so build doesn't break)**

Create `src/app/features/obligations/obligations.component.ts`:

```typescript
import { Component } from '@angular/core';

@Component({
  selector: 'app-obligations',
  template: `<p class="text-zinc-500">Obligations loading...</p>`,
})
export class ObligationsComponent {}
```

- [ ] **Step 5.5: Build to verify routing is wired**

```bash
ng build --configuration development
```

Expected: Build succeeds.

- [ ] **Step 5.6: Commit**

```bash
git add src/app/shared/layout/ src/app/app.routes.ts src/app/features/obligations/
git rm src/app/features/dashboard/dashboard.component.ts \
       src/app/features/dashboard/dashboard.component.html \
       src/app/features/dashboard/dashboard.component.scss
git commit -m "feat(frontend): add LayoutComponent, restructure routes, remove DashboardComponent"
```

---

## Task 6: SidebarComponent

**Files:**
- Create: `src/app/shared/layout/sidebar/sidebar.component.ts`
- Create: `src/app/shared/layout/sidebar/sidebar.component.html`

- [ ] **Step 6.1: Create SidebarComponent**

Create `src/app/shared/layout/sidebar/sidebar.component.ts`:

```typescript
import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { ThemeService } from '../../theme.service';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-sidebar',
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './sidebar.component.html',
})
export class SidebarComponent {
  theme = inject(ThemeService);
  private auth = inject(AuthService);
  private router = inject(Router);

  signOut(): void {
    this.auth.logout().subscribe({
      complete: () => this.router.navigate(['/login']),
      error: () => this.router.navigate(['/login']),
    });
  }
}
```

Create `src/app/shared/layout/sidebar/sidebar.component.html`:

```html
<aside class="w-[170px] flex-shrink-0 flex flex-col border-r border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950 min-h-screen p-4">

  <!-- Wordmark -->
  <span class="font-bold text-lg text-zinc-900 dark:text-zinc-50 mb-7">FinanceHQ</span>

  <!-- Nav -->
  <p class="text-[10px] font-semibold text-zinc-400 uppercase tracking-widest mb-2">Menu</p>
  <nav class="flex flex-col gap-1">
    <a routerLink="/dashboard" routerLinkActive="bg-zinc-100 dark:bg-zinc-800 font-semibold"
       class="flex items-center gap-2 px-3 py-2 rounded-md text-sm text-zinc-700 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors">
      Obligations
    </a>
  </nav>

  <!-- Bottom -->
  <div class="mt-auto flex flex-col gap-2 pt-4 border-t border-zinc-200 dark:border-zinc-800">
    <!-- Dark mode toggle -->
    <button (click)="theme.toggle()"
            class="flex items-center gap-2 px-3 py-2 rounded-md text-sm text-zinc-500 dark:text-zinc-400 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors w-full text-left">
      @if (theme.isDark()) {
        ☀️ Light mode
      } @else {
        🌙 Dark mode
      }
    </button>
    <!-- Sign out -->
    <button (click)="signOut()"
            class="flex items-center gap-2 px-3 py-2 rounded-md text-sm text-zinc-500 dark:text-zinc-400 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors w-full text-left">
      Sign out
    </button>
  </div>
</aside>
```

- [ ] **Step 6.2: Build to verify**

```bash
ng build --configuration development
```

Expected: Build succeeds.

- [ ] **Step 6.3: Commit**

```bash
git add src/app/shared/layout/sidebar/
git commit -m "feat(frontend): add SidebarComponent with nav, dark toggle, sign out"
```

---

## Task 7: CategoryBadgeComponent

**Files:**
- Create: `src/app/shared/ui/category-badge/category-badge.component.ts`
- Create: `src/app/shared/ui/category-badge/category-badge.component.html`

- [ ] **Step 7.1: Create CategoryBadgeComponent**

Create `src/app/shared/ui/category-badge/category-badge.component.ts`:

```typescript
import { Component, Input } from '@angular/core';

type Category = 'TOP' | 'HIGH' | 'LOW';

@Component({
  selector: 'app-category-badge',
  templateUrl: './category-badge.component.html',
})
export class CategoryBadgeComponent {
  @Input({ required: true }) category!: Category;

  get classes(): string {
    const map: Record<Category, string> = {
      TOP: 'bg-red-50 text-red-600 border-red-200 dark:bg-red-950 dark:text-red-400 dark:border-red-900',
      HIGH: 'bg-amber-50 text-amber-600 border-amber-200 dark:bg-amber-950 dark:text-amber-400 dark:border-amber-900',
      LOW: 'bg-green-50 text-green-600 border-green-200 dark:bg-green-950 dark:text-green-400 dark:border-green-900',
    };
    return map[this.category];
  }
}
```

Create `src/app/shared/ui/category-badge/category-badge.component.html`:

```html
<span class="inline-flex items-center px-2 py-0.5 rounded border text-xs font-semibold {{ classes }}">
  {{ category }}
</span>
```

- [ ] **Step 7.2: Build to verify**

```bash
ng build --configuration development
```

Expected: Build succeeds.

- [ ] **Step 7.3: Commit**

```bash
git add src/app/shared/ui/category-badge/
git commit -m "feat(frontend): add CategoryBadgeComponent with light/dark tokens"
```

---

## Task 8: Login Page Rebuild

**Files:**
- Modify: `src/app/features/login/login.component.ts`
- Modify: `src/app/features/login/login.component.html`
- Modify: `src/app/features/login/login.component.scss`

- [ ] **Step 8.1: Replace `login.component.ts`**

```typescript
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);

  form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  loading = signal(false);
  error = signal('');
  showPassword = signal(false);

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set('');
    const { email, password } = this.form.getRawValue();
    this.auth.login(email!, password!).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      error: (err) => {
        this.loading.set(false);
        this.error.set(
          err.status === 401
            ? 'Invalid email or password.'
            : 'Something went wrong. Please try again.',
        );
      },
    });
  }
}
```

- [ ] **Step 8.2: Replace `login.component.html`**

```html
<div class="min-h-screen flex items-center justify-center bg-zinc-100 dark:bg-zinc-900 px-4">
  <div class="w-80 bg-white dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-xl p-6 shadow-sm">

    <h1 class="text-xl font-bold text-zinc-900 dark:text-zinc-50 mb-1">Welcome back</h1>
    <p class="text-sm text-zinc-500 mb-6">Sign in to FinanceHQ</p>

    <form [formGroup]="form" (ngSubmit)="submit()" class="flex flex-col gap-4">

      <!-- Email -->
      <div class="flex flex-col gap-1">
        <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Email</label>
        <input formControlName="email" type="email" placeholder="you@example.com"
               class="border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 placeholder-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-400"
               [class.border-red-400]="form.controls.email.invalid && form.controls.email.touched" />
        @if (form.controls.email.invalid && form.controls.email.touched) {
          <p class="text-xs text-red-500">Enter a valid email address.</p>
        }
      </div>

      <!-- Password -->
      <div class="flex flex-col gap-1">
        <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Password</label>
        <div class="relative">
          <input formControlName="password" [type]="showPassword() ? 'text' : 'password'"
                 class="w-full border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 pr-10 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 placeholder-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-400"
                 [class.border-red-400]="form.controls.password.invalid && form.controls.password.touched" />
          <button type="button" (click)="showPassword.set(!showPassword())"
                  class="absolute right-2 top-1/2 -translate-y-1/2 text-zinc-400 hover:text-zinc-600 text-xs px-1">
            {{ showPassword() ? 'Hide' : 'Show' }}
          </button>
        </div>
        @if (form.controls.password.invalid && form.controls.password.touched) {
          <p class="text-xs text-red-500">Password is required.</p>
        }
      </div>

      <!-- Server error -->
      @if (error()) {
        <div class="bg-red-50 dark:bg-red-950 border border-red-200 dark:border-red-800 rounded-md px-3 py-2 text-sm text-red-600 dark:text-red-400">
          {{ error() }}
        </div>
      }

      <!-- Submit -->
      <button type="submit" [disabled]="loading()"
              class="w-full bg-zinc-900 dark:bg-zinc-50 text-white dark:text-zinc-900 font-semibold rounded-md py-2 text-sm hover:bg-zinc-700 dark:hover:bg-zinc-200 transition-colors disabled:opacity-50 disabled:cursor-not-allowed">
        @if (loading()) { Signing in… } @else { Sign in }
      </button>

    </form>

    <p class="text-center text-xs text-zinc-500 mt-5">
      Don't have an account?
      <a routerLink="/register" class="text-zinc-900 dark:text-zinc-50 font-semibold hover:underline">Register</a>
    </p>
  </div>
</div>
```

- [ ] **Step 8.3: Clear `login.component.scss`**

```scss
// no component styles — Tailwind only
```

- [ ] **Step 8.4: Build to verify**

```bash
ng build --configuration development
```

Expected: Build succeeds.

- [ ] **Step 8.5: Manual smoke test**

```bash
ng serve
```

Navigate to `http://localhost:4200/login`. Verify:
- Card renders on `zinc-100` background
- Email + password fields render correctly
- Leaving fields blank and submitting shows inline errors
- "Show/Hide" toggles password visibility

- [ ] **Step 8.6: Commit**

```bash
git add src/app/features/login/
git commit -m "feat(frontend): rebuild Login page with Tailwind + spartan/ui"
```

---

## Task 9: Register Page Rebuild

**Files:**
- Modify: `src/app/features/register/register.component.ts`
- Modify: `src/app/features/register/register.component.html`
- Modify: `src/app/features/register/register.component.scss`

- [ ] **Step 9.1: Replace `register.component.ts`**

The existing component has `MatSnackBar`, `ngOnDestroy`, and `formChangeSub`. Remove all of these and replace with inline error state:

```typescript
import { Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

function passwordStrength(control: AbstractControl): ValidationErrors | null {
  const v: string = control.value ?? '';
  const errors: ValidationErrors = {};
  if (v.length < 8) errors['pwLength'] = true;
  if (!/[A-Z]/.test(v)) errors['pwUppercase'] = true;
  if (!/\d/.test(v)) errors['pwDigit'] = true;
  if (!/[^A-Za-z0-9]/.test(v)) errors['pwSpecial'] = true;
  return Object.keys(errors).length ? errors : null;
}

function passwordMatch(group: AbstractControl): ValidationErrors | null {
  const pw = group.get('password')?.value;
  const confirm = group.get('confirmPassword')?.value;
  return pw && confirm && pw !== confirm ? { passwordMismatch: true } : null;
}

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register.component.html',
  styleUrl: './register.component.scss',
})
export class RegisterComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);

  form = this.fb.group(
    {
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, passwordStrength]],
      confirmPassword: ['', Validators.required],
    },
    { validators: passwordMatch },
  );

  loading = signal(false);
  error = signal('');
  showPassword = signal(false);
  showConfirm = signal(false);
  passwordFocused = signal(false);

  get pw() { return this.form.controls.password; }
  get pwHints() {
    return [
      { key: 'pwLength', label: '≥ 8 characters' },
      { key: 'pwUppercase', label: '1 uppercase letter' },
      { key: 'pwDigit', label: '1 digit' },
      { key: 'pwSpecial', label: '1 special character' },
    ];
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set('');
    const { email, password } = this.form.getRawValue();
    this.auth.register(email!, password!).subscribe({
      next: () => this.router.navigate(['/login']),
      error: (err) => {
        this.loading.set(false);
        this.error.set(
          err.status === 409
            ? 'An account with this email already exists.'
            : 'Something went wrong. Please try again.',
        );
      },
    });
  }
}
```

- [ ] **Step 9.2: Replace `register.component.html`**

```html
<div class="min-h-screen flex items-center justify-center bg-zinc-100 dark:bg-zinc-900 px-4">
  <div class="w-80 bg-white dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-xl p-6 shadow-sm">

    <h1 class="text-xl font-bold text-zinc-900 dark:text-zinc-50 mb-1">Create account</h1>
    <p class="text-sm text-zinc-500 mb-6">Start tracking your obligations</p>

    <form [formGroup]="form" (ngSubmit)="submit()" class="flex flex-col gap-4">

      <!-- Email -->
      <div class="flex flex-col gap-1">
        <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Email</label>
        <input formControlName="email" type="email" placeholder="you@example.com"
               class="border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 placeholder-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-400"
               [class.border-red-400]="form.controls.email.invalid && form.controls.email.touched" />
        @if (form.controls.email.invalid && form.controls.email.touched) {
          <p class="text-xs text-red-500">Enter a valid email address.</p>
        }
      </div>

      <!-- Password -->
      <div class="flex flex-col gap-1">
        <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Password</label>
        <div class="relative">
          <input formControlName="password" [type]="showPassword() ? 'text' : 'password'"
                 (focus)="passwordFocused.set(true)" (blur)="passwordFocused.set(false)"
                 class="w-full border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 pr-10 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 placeholder-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-400"
                 [class.border-red-400]="pw.invalid && pw.touched" />
          <button type="button" (click)="showPassword.set(!showPassword())"
                  class="absolute right-2 top-1/2 -translate-y-1/2 text-zinc-400 hover:text-zinc-600 text-xs px-1">
            {{ showPassword() ? 'Hide' : 'Show' }}
          </button>
        </div>
        <!-- Password hints shown on focus -->
        @if (passwordFocused() || (pw.dirty && pw.invalid)) {
          <ul class="mt-1 space-y-0.5">
            @for (hint of pwHints; track hint.key) {
              <li class="text-xs" [class.text-green-500]="!pw.hasError(hint.key)" [class.text-zinc-400]="pw.hasError(hint.key)">
                {{ hint.label }}
              </li>
            }
          </ul>
        }
      </div>

      <!-- Confirm Password -->
      <div class="flex flex-col gap-1">
        <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Confirm password</label>
        <div class="relative">
          <input formControlName="confirmPassword" [type]="showConfirm() ? 'text' : 'password'"
                 class="w-full border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 pr-10 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 placeholder-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-400"
                 [class.border-red-400]="form.hasError('passwordMismatch') && form.controls.confirmPassword.touched" />
          <button type="button" (click)="showConfirm.set(!showConfirm())"
                  class="absolute right-2 top-1/2 -translate-y-1/2 text-zinc-400 hover:text-zinc-600 text-xs px-1">
            {{ showConfirm() ? 'Hide' : 'Show' }}
          </button>
        </div>
        @if (form.hasError('passwordMismatch') && form.controls.confirmPassword.touched) {
          <p class="text-xs text-red-500">Passwords do not match.</p>
        }
      </div>

      <!-- Server error -->
      @if (error()) {
        <div class="bg-red-50 dark:bg-red-950 border border-red-200 dark:border-red-800 rounded-md px-3 py-2 text-sm text-red-600 dark:text-red-400">
          {{ error() }}
        </div>
      }

      <!-- Submit -->
      <button type="submit" [disabled]="loading() || form.invalid"
              class="w-full bg-zinc-900 dark:bg-zinc-50 text-white dark:text-zinc-900 font-semibold rounded-md py-2 text-sm hover:bg-zinc-700 dark:hover:bg-zinc-200 transition-colors disabled:opacity-50 disabled:cursor-not-allowed">
        @if (loading()) { Creating account… } @else { Create account }
      </button>

    </form>

    <p class="text-center text-xs text-zinc-500 mt-5">
      Already have an account?
      <a routerLink="/login" class="text-zinc-900 dark:text-zinc-50 font-semibold hover:underline">Sign in</a>
    </p>
  </div>
</div>
```

- [ ] **Step 9.3: Clear `register.component.scss`**

```scss
// no component styles — Tailwind only
```

- [ ] **Step 9.4: Build to verify**

```bash
ng build --configuration development
```

Expected: Build succeeds.

- [ ] **Step 9.5: Manual smoke test**

```bash
ng serve
```

Navigate to `http://localhost:4200/register`. Verify:
- Password hint list appears on focus and turns green as requirements are met
- Mismatch error appears when confirm password doesn't match
- Submit button disabled until form valid

- [ ] **Step 9.6: Commit**

```bash
git add src/app/features/register/
git commit -m "feat(frontend): rebuild Register page — remove MatSnackBar, add password hints"
```

---

## Task 10: Obligation Model + ObligationsService

**Files:**
- Create: `src/app/features/obligations/obligation.model.ts`
- Create: `src/app/features/obligations/obligations.service.ts`
- Create: `src/app/features/obligations/obligations.service.spec.ts`

- [ ] **Step 10.1: Create obligation.model.ts**

```typescript
// src/app/features/obligations/obligation.model.ts
export interface Obligation {
  id: string;
  name: string;
  amount: number;
  category: 'TOP' | 'HIGH' | 'LOW';
  period: 'RECURRING' | 'FIXED_TERM';
  paymentDay: number;
  createdAt: string;
}

export type CreateObligationDto = Omit<Obligation, 'id' | 'createdAt'>;
export type UpdateObligationDto = Pick<Obligation, 'amount' | 'paymentDay'>;
```

- [ ] **Step 10.2: Write failing tests for ObligationsService**

Create `src/app/features/obligations/obligations.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ObligationsService } from './obligations.service';
import { Obligation, CreateObligationDto, UpdateObligationDto } from './obligation.model';

const mockObligation: Obligation = {
  id: '1',
  name: 'Rent',
  amount: 1200,
  category: 'TOP',
  period: 'RECURRING',
  paymentDay: 15,
  createdAt: '2026-01-01T00:00:00Z',
};

describe('ObligationsService', () => {
  let service: ObligationsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ObligationsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('should GET /api/obligations', () => {
    service.getAll().subscribe(result => expect(result).toEqual([mockObligation]));
    http.expectOne('/api/obligations').flush([mockObligation]);
  });

  it('should POST /api/obligations', () => {
    const dto: CreateObligationDto = { name: 'Rent', amount: 1200, category: 'TOP', period: 'RECURRING', paymentDay: 15 };
    service.create(dto).subscribe(result => expect(result).toEqual(mockObligation));
    const req = http.expectOne('/api/obligations');
    expect(req.request.method).toBe('POST');
    req.flush(mockObligation);
  });

  it('should PATCH /api/obligations/:id', () => {
    const dto: UpdateObligationDto = { amount: 1300, paymentDay: 20 };
    service.update('1', dto).subscribe(result => expect(result).toEqual(mockObligation));
    const req = http.expectOne('/api/obligations/1');
    expect(req.request.method).toBe('PATCH');
    req.flush(mockObligation);
  });

  it('should DELETE /api/obligations/:id', () => {
    service.delete('1').subscribe();
    const req = http.expectOne('/api/obligations/1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
```

- [ ] **Step 10.3: Run tests to verify they fail**

```bash
ng test --include="**/obligations.service.spec.ts" --watch=false
```

Expected: FAIL — `ObligationsService` not found.

- [ ] **Step 10.4: Implement ObligationsService**

Create `src/app/features/obligations/obligations.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Obligation, CreateObligationDto, UpdateObligationDto } from './obligation.model';

@Injectable({ providedIn: 'root' })
export class ObligationsService {
  private http = inject(HttpClient);
  private base = '/api/obligations';

  getAll(): Observable<Obligation[]> {
    return this.http.get<Obligation[]>(this.base);
  }

  create(dto: CreateObligationDto): Observable<Obligation> {
    return this.http.post<Obligation>(this.base, dto);
  }

  update(id: string, dto: UpdateObligationDto): Observable<Obligation> {
    return this.http.patch<Obligation>(`${this.base}/${id}`, dto);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
```

- [ ] **Step 10.5: Run tests to verify they pass**

```bash
ng test --include="**/obligations.service.spec.ts" --watch=false
```

Expected: 4 tests PASS.

- [ ] **Step 10.6: Commit**

```bash
git add src/app/features/obligations/obligation.model.ts \
        src/app/features/obligations/obligations.service.ts \
        src/app/features/obligations/obligations.service.spec.ts
git commit -m "feat(frontend): add Obligation model and ObligationsService with tests"
```

---

## Task 11: ObligationsComponent — List + Empty State

**Files:**
- Modify: `src/app/features/obligations/obligations.component.ts`
- Modify: `src/app/features/obligations/obligations.component.html`

- [ ] **Step 11.1: Replace `obligations.component.ts`**

```typescript
import { Component, inject, signal, OnInit } from '@angular/core';
import { ObligationsService } from './obligations.service';
import { Obligation } from './obligation.model';
import { CategoryBadgeComponent } from '../../shared/ui/category-badge/category-badge.component';

@Component({
  selector: 'app-obligations',
  imports: [CategoryBadgeComponent],
  templateUrl: './obligations.component.html',
})
export class ObligationsComponent implements OnInit {
  private svc = inject(ObligationsService);

  obligations = signal<Obligation[]>([]);
  loading = signal(true);
  error = signal('');

  // Dialog state
  showAddEdit = signal(false);
  showDelete = signal(false);
  editing = signal<Obligation | null>(null);
  deleting = signal<Obligation | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.svc.getAll().subscribe({
      next: (data) => { this.obligations.set(data); this.loading.set(false); },
      error: () => { this.error.set('Failed to load obligations.'); this.loading.set(false); },
    });
  }

  openAdd(): void {
    this.editing.set(null);
    this.showAddEdit.set(true);
  }

  openEdit(o: Obligation): void {
    this.editing.set(o);
    this.showAddEdit.set(true);
  }

  openDelete(o: Obligation): void {
    this.deleting.set(o);
    this.showDelete.set(true);
  }

  onSaved(): void {
    this.showAddEdit.set(false);
    this.load();
  }

  onDeleted(): void {
    this.showDelete.set(false);
    this.load();
  }

  ordinal(day: number): string {
    const s = ['th', 'st', 'nd', 'rd'];
    const v = day % 100;
    return day + (s[(v - 20) % 10] ?? s[v] ?? s[0]);
  }

  periodLabel(period: string): string {
    return period === 'RECURRING' ? 'Recurring monthly' : 'Fixed term';
  }
}
```

- [ ] **Step 11.2: Replace `obligations.component.html`**

```html
<div>
  <!-- Header -->
  <div class="flex items-start justify-between mb-6">
    <div>
      <h1 class="text-xl font-bold text-zinc-900 dark:text-zinc-50">Obligations</h1>
      <p class="text-sm text-zinc-500">{{ obligations().length }} active obligation{{ obligations().length === 1 ? '' : 's' }}</p>
    </div>
    <button (click)="openAdd()"
            class="bg-zinc-900 dark:bg-zinc-50 text-white dark:text-zinc-900 font-semibold rounded-md px-4 py-2 text-sm hover:bg-zinc-700 dark:hover:bg-zinc-200 transition-colors">
      + Add Obligation
    </button>
  </div>

  <!-- Loading -->
  @if (loading()) {
    <p class="text-sm text-zinc-500">Loading…</p>
  }

  <!-- Error -->
  @if (error()) {
    <div class="bg-red-50 dark:bg-red-950 border border-red-200 dark:border-red-800 rounded-md px-4 py-3 text-sm text-red-600 dark:text-red-400">
      {{ error() }}
    </div>
  }

  <!-- Empty state -->
  @if (!loading() && !error() && obligations().length === 0) {
    <div class="flex flex-col items-center justify-center py-20 text-center">
      <p class="text-zinc-500 mb-4">No obligations yet. Add your first one.</p>
      <button (click)="openAdd()"
              class="bg-zinc-900 dark:bg-zinc-50 text-white dark:text-zinc-900 font-semibold rounded-md px-4 py-2 text-sm">
        + Add Obligation
      </button>
    </div>
  }

  <!-- Table -->
  @if (!loading() && obligations().length > 0) {
    <div class="border border-zinc-200 dark:border-zinc-800 rounded-lg overflow-hidden">
      <!-- Header -->
      <div class="grid grid-cols-[2fr_1fr_1fr_1fr_64px] bg-zinc-50 dark:bg-zinc-900 border-b-2 border-zinc-200 dark:border-zinc-700 px-4 py-2.5">
        @for (col of ['NAME','AMOUNT','DUE DAY','CATEGORY','ACTIONS']; track col) {
          <span class="text-[11px] font-bold text-zinc-500 tracking-wider">{{ col }}</span>
        }
      </div>
      <!-- Rows -->
      @for (o of obligations(); track o.id) {
        <div class="grid grid-cols-[2fr_1fr_1fr_1fr_64px] px-4 py-3 items-center border-b border-zinc-100 dark:border-zinc-800 last:border-0 bg-white dark:bg-zinc-950 hover:bg-zinc-50 dark:hover:bg-zinc-900 transition-colors">
          <div>
            <p class="font-semibold text-sm text-zinc-900 dark:text-zinc-50">{{ o.name }}</p>
            <p class="text-xs text-zinc-500">{{ periodLabel(o.period) }}</p>
          </div>
          <p class="text-sm font-semibold text-zinc-900 dark:text-zinc-50">${{ o.amount.toLocaleString() }}</p>
          <p class="text-sm text-zinc-700 dark:text-zinc-300">{{ ordinal(o.paymentDay) }}</p>
          <app-category-badge [category]="o.category" />
          <div class="flex gap-1.5">
            <button (click)="openEdit(o)"
                    class="bg-zinc-100 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded px-2 py-1 text-xs text-zinc-600 dark:text-zinc-400 hover:bg-zinc-200 dark:hover:bg-zinc-700 transition-colors"
                    title="Edit">✏️</button>
            <button (click)="openDelete(o)"
                    class="bg-red-50 dark:bg-red-950 border border-red-200 dark:border-red-900 rounded px-2 py-1 text-xs text-red-500 hover:bg-red-100 dark:hover:bg-red-900 transition-colors"
                    title="Delete">🗑</button>
          </div>
        </div>
      }
    </div>
  }

  <!-- Dialogs rendered here in Task 12 and 13 -->
</div>
```

- [ ] **Step 11.3: Build and manually test**

```bash
ng build --configuration development
ng serve
```

Log in and navigate to `/dashboard`. Verify:
- Empty state shows with "+ Add Obligation" when no obligations
- Table header renders correctly
- Dark mode toggle works (sidebar button)

- [ ] **Step 11.4: Commit**

```bash
git add src/app/features/obligations/obligations.component.ts \
        src/app/features/obligations/obligations.component.html
git commit -m "feat(frontend): ObligationsComponent — table, empty state, list"
```

---

## Task 12: Add/Edit Obligation Dialog

**Files:**
- Create: `src/app/features/obligations/obligation-dialog/obligation-dialog.component.ts`
- Create: `src/app/features/obligations/obligation-dialog/obligation-dialog.component.html`
- Modify: `src/app/features/obligations/obligations.component.ts` (import dialog)
- Modify: `src/app/features/obligations/obligations.component.html` (render dialog)

- [ ] **Step 12.1: Create ObligationDialogComponent**

Create `src/app/features/obligations/obligation-dialog/obligation-dialog.component.ts`:

```typescript
import { Component, Input, Output, EventEmitter, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Obligation } from '../obligation.model';
import { ObligationsService } from '../obligations.service';

@Component({
  selector: 'app-obligation-dialog',
  imports: [ReactiveFormsModule],
  templateUrl: './obligation-dialog.component.html',
})
export class ObligationDialogComponent implements OnInit {
  @Input() obligation: Obligation | null = null; // null = add mode
  @Output() saved = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private svc = inject(ObligationsService);

  loading = signal(false);
  error = signal('');

  form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01), Validators.max(999999.99)]],
    category: ['TOP' as 'TOP' | 'HIGH' | 'LOW', Validators.required],
    period: ['RECURRING' as 'RECURRING' | 'FIXED_TERM', Validators.required],
    paymentDay: [null as number | null, [Validators.required, Validators.min(1), Validators.max(31)]],
  });

  get isEdit(): boolean { return this.obligation !== null; }

  ngOnInit(): void {
    if (this.obligation) {
      this.form.patchValue({
        name: this.obligation.name,
        amount: this.obligation.amount,
        category: this.obligation.category,
        period: this.obligation.period,
        paymentDay: this.obligation.paymentDay,
      });
      // lock read-only fields in edit mode
      this.form.controls.name.disable();
      this.form.controls.category.disable();
      this.form.controls.period.disable();
    }
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set('');
    const v = this.form.getRawValue();

    const call = this.isEdit
      ? this.svc.update(this.obligation!.id, { amount: v.amount!, paymentDay: v.paymentDay! })
      : this.svc.create({ name: v.name!, amount: v.amount!, category: v.category!, period: v.period!, paymentDay: v.paymentDay! });

    call.subscribe({
      next: () => this.saved.emit(),
      error: () => { this.loading.set(false); this.error.set('Failed to save. Please try again.'); },
    });
  }
}
```

- [ ] **Step 12.2: Create dialog template**

Create `src/app/features/obligations/obligation-dialog/obligation-dialog.component.html`:

```html
<!-- Backdrop -->
<div class="fixed inset-0 bg-black/40 flex items-center justify-center z-50 px-4">
  <div class="bg-white dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-xl p-6 w-full max-w-sm shadow-xl">

    <h2 class="text-base font-bold text-zinc-900 dark:text-zinc-50 mb-1">
      {{ isEdit ? 'Edit Obligation' : 'Add Obligation' }}
    </h2>
    <p class="text-xs text-zinc-500 mb-5">Fill in the details for your obligation</p>

    <form [formGroup]="form" (ngSubmit)="submit()" class="flex flex-col gap-4">

      <!-- Row 1: Name + Amount -->
      <div class="grid grid-cols-2 gap-3">
        <div class="flex flex-col gap-1">
          <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Name</label>
          <input formControlName="name" type="text" placeholder="e.g. Rent"
                 class="border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 placeholder-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-400 disabled:bg-zinc-50 dark:disabled:bg-zinc-900 disabled:text-zinc-400"
                 [class.border-red-400]="form.controls.name.invalid && form.controls.name.touched" />
          @if (form.controls.name.invalid && form.controls.name.touched) {
            <p class="text-xs text-red-500">Required (max 100 chars)</p>
          }
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Amount ($)</label>
          <input formControlName="amount" type="number" step="0.01" placeholder="0.00"
                 class="border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 placeholder-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-400"
                 [class.border-red-400]="form.controls.amount.invalid && form.controls.amount.touched" />
          @if (form.controls.amount.invalid && form.controls.amount.touched) {
            <p class="text-xs text-red-500">Enter amount (0.01–999,999.99)</p>
          }
        </div>
      </div>

      <!-- Row 2: Category + Period -->
      <div class="grid grid-cols-2 gap-3">
        <div class="flex flex-col gap-1">
          <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Category</label>
          <select formControlName="category"
                  class="border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 focus:outline-none focus:ring-2 focus:ring-zinc-400 disabled:bg-zinc-50 dark:disabled:bg-zinc-900 disabled:text-zinc-400">
            <option value="TOP">TOP</option>
            <option value="HIGH">HIGH</option>
            <option value="LOW">LOW</option>
          </select>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Period</label>
          <select formControlName="period"
                  class="border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 focus:outline-none focus:ring-2 focus:ring-zinc-400 disabled:bg-zinc-50 dark:disabled:bg-zinc-900 disabled:text-zinc-400">
            <option value="RECURRING">Recurring</option>
            <option value="FIXED_TERM">Fixed term</option>
          </select>
        </div>
      </div>

      <!-- Payment day -->
      <div class="flex flex-col gap-1">
        <label class="text-xs font-semibold text-zinc-700 dark:text-zinc-300">Payment day of month</label>
        <input formControlName="paymentDay" type="number" min="1" max="31" placeholder="1–31"
               class="border border-zinc-200 dark:border-zinc-700 rounded-md px-3 py-2 text-sm bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-50 placeholder-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-400"
               [class.border-red-400]="form.controls.paymentDay.invalid && form.controls.paymentDay.touched" />
        @if (form.controls.paymentDay.invalid && form.controls.paymentDay.touched) {
          <p class="text-xs text-red-500">Enter a day between 1 and 31</p>
        }
      </div>

      <!-- Error -->
      @if (error()) {
        <div class="bg-red-50 dark:bg-red-950 border border-red-200 dark:border-red-800 rounded-md px-3 py-2 text-sm text-red-600 dark:text-red-400">
          {{ error() }}
        </div>
      }

      <!-- Actions -->
      <div class="flex justify-end gap-2 pt-1">
        <button type="button" (click)="cancelled.emit()"
                class="border border-zinc-200 dark:border-zinc-700 rounded-md px-4 py-2 text-sm text-zinc-600 dark:text-zinc-400 hover:bg-zinc-50 dark:hover:bg-zinc-900 transition-colors">
          Cancel
        </button>
        <button type="submit" [disabled]="loading() || form.invalid"
                class="bg-zinc-900 dark:bg-zinc-50 text-white dark:text-zinc-900 font-semibold rounded-md px-4 py-2 text-sm hover:bg-zinc-700 dark:hover:bg-zinc-200 transition-colors disabled:opacity-50 disabled:cursor-not-allowed">
          @if (loading()) { Saving… } @else { Save }
        </button>
      </div>

    </form>
  </div>
</div>
```

- [ ] **Step 12.3: Wire dialog into ObligationsComponent**

In `obligations.component.ts`, add to imports array:
```typescript
import { ObligationDialogComponent } from './obligation-dialog/obligation-dialog.component';
// add ObligationDialogComponent to the imports: [...] array in @Component
```

In `obligations.component.html`, append before the closing `</div>`:

```html
@if (showAddEdit()) {
  <app-obligation-dialog
    [obligation]="editing()"
    (saved)="onSaved()"
    (cancelled)="showAddEdit.set(false)" />
}
```

- [ ] **Step 12.4: Build and manually test**

```bash
ng build --configuration development
ng serve
```

Test:
- Click "+ Add Obligation" → modal opens
- Fill form → click Save → obligation appears in table
- Click edit icon on a row → modal opens pre-filled with Name/Category/Period locked
- Edit Amount + Payment Day → Save → table refreshes

- [ ] **Step 12.5: Commit**

```bash
git add src/app/features/obligations/obligation-dialog/
git add src/app/features/obligations/obligations.component.ts \
        src/app/features/obligations/obligations.component.html
git commit -m "feat(frontend): Add/Edit Obligation dialog"
```

---

## Task 13: Delete Confirmation Dialog

**Files:**
- Create: `src/app/features/obligations/delete-dialog/delete-dialog.component.ts`
- Create: `src/app/features/obligations/delete-dialog/delete-dialog.component.html`
- Modify: `src/app/features/obligations/obligations.component.ts`
- Modify: `src/app/features/obligations/obligations.component.html`

- [ ] **Step 13.1: Create DeleteDialogComponent**

Create `src/app/features/obligations/delete-dialog/delete-dialog.component.ts`:

```typescript
import { Component, Input, Output, EventEmitter, inject, signal } from '@angular/core';
import { Obligation } from '../obligation.model';
import { ObligationsService } from '../obligations.service';

@Component({
  selector: 'app-delete-dialog',
  templateUrl: './delete-dialog.component.html',
})
export class DeleteDialogComponent {
  @Input({ required: true }) obligation!: Obligation;
  @Output() deleted = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  private svc = inject(ObligationsService);
  loading = signal(false);

  confirm(): void {
    this.loading.set(true);
    this.svc.delete(this.obligation.id).subscribe({
      next: () => this.deleted.emit(),
      error: () => this.loading.set(false),
    });
  }
}
```

Create `src/app/features/obligations/delete-dialog/delete-dialog.component.html`:

```html
<div class="fixed inset-0 bg-black/40 flex items-center justify-center z-50 px-4">
  <div class="bg-white dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded-xl p-6 w-full max-w-xs shadow-xl">
    <h2 class="text-base font-bold text-zinc-900 dark:text-zinc-50 mb-2">Delete obligation?</h2>
    <p class="text-sm text-zinc-500 mb-6">
      "<span class="font-semibold text-zinc-700 dark:text-zinc-300">{{ obligation.name }}</span>" will be permanently deleted.
    </p>
    <div class="flex justify-end gap-2">
      <button (click)="cancelled.emit()"
              class="border border-zinc-200 dark:border-zinc-700 rounded-md px-4 py-2 text-sm text-zinc-600 dark:text-zinc-400 hover:bg-zinc-50 dark:hover:bg-zinc-900 transition-colors">
        Cancel
      </button>
      <button (click)="confirm()" [disabled]="loading()"
              class="bg-red-600 text-white font-semibold rounded-md px-4 py-2 text-sm hover:bg-red-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed">
        @if (loading()) { Deleting… } @else { Delete }
      </button>
    </div>
  </div>
</div>
```

- [ ] **Step 13.2: Wire into ObligationsComponent**

Add `DeleteDialogComponent` to imports in `obligations.component.ts`.

Append to `obligations.component.html` before closing `</div>`:

```html
@if (showDelete() && deleting()) {
  <app-delete-dialog
    [obligation]="deleting()!"
    (deleted)="onDeleted()"
    (cancelled)="showDelete.set(false)" />
}
```

- [ ] **Step 13.3: Build and manually test**

```bash
ng build --configuration development
ng serve
```

Test:
- Click delete icon → confirmation dialog opens with obligation name
- Click Cancel → dialog closes, no change
- Click Delete → dialog shows "Deleting…" → closes → obligation removed from table

- [ ] **Step 13.4: Commit**

```bash
git add src/app/features/obligations/delete-dialog/
git add src/app/features/obligations/obligations.component.ts \
        src/app/features/obligations/obligations.component.html
git commit -m "feat(frontend): delete confirmation dialog (FR-006)"
```

---

## Task 14: Not Found Page Cleanup

**Files:**
- Modify: `src/app/features/not-found/not-found.component.html`
- Modify: `src/app/features/not-found/not-found.component.ts`
- Modify: `src/app/features/not-found/not-found.component.scss`

- [ ] **Step 14.1: Replace not-found component**

Replace `not-found.component.ts`:

```typescript
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  templateUrl: './not-found.component.html',
  styleUrl: './not-found.component.scss',
})
export class NotFoundComponent {}
```

Replace `not-found.component.html`:

```html
<div class="min-h-screen flex flex-col items-center justify-center bg-zinc-100 dark:bg-zinc-900 px-4">
  <h1 class="text-4xl font-bold text-zinc-900 dark:text-zinc-50 mb-2">404</h1>
  <p class="text-zinc-500 mb-6">Page not found.</p>
  <a routerLink="/dashboard"
     class="bg-zinc-900 dark:bg-zinc-50 text-white dark:text-zinc-900 font-semibold rounded-md px-4 py-2 text-sm hover:bg-zinc-700 dark:hover:bg-zinc-200 transition-colors">
    Back to dashboard
  </a>
</div>
```

Clear `not-found.component.scss`:

```scss
// no component styles
```

- [ ] **Step 14.2: Final full build + run all tests**

```bash
ng build --configuration development
ng test --watch=false
```

Expected: Build succeeds. All tests pass.

- [ ] **Step 14.3: Commit**

```bash
git add src/app/features/not-found/
git commit -m "feat(frontend): clean up Not Found page"
```

---

## Task 15: End-to-End Smoke Test

- [ ] **Step 15.1: Start backend and frontend**

```bash
# Terminal 1 — from project root
java -jar target/finance-hq-*.jar --spring.profiles.active=local

# Terminal 2 — from src/main/frontend
ng serve
```

- [ ] **Step 15.2: Walk the golden path**

1. Navigate to `http://localhost:4200` → redirects to `/login`
2. Click Register → fill form → create account → redirect to `/login`
3. Log in → redirect to `/dashboard` → sidebar visible, obligations empty state shown
4. Click "+ Add Obligation" → fill modal → Save → obligation in table with correct badge
5. Click edit icon → modal pre-filled, Name/Category/Period locked → change amount → Save → table updated
6. Click delete icon → confirmation dialog shows obligation name → Delete → removed from table
7. Toggle dark mode → sidebar + table + modal all switch to dark palette
8. Sign out → redirect to `/login`

- [ ] **Step 15.3: Final commit**

```bash
git add -A
git commit -m "chore(frontend): frontend rebuild complete — spartan/ui + Tailwind"
```
