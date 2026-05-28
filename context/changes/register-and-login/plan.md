# Register and Login Implementation Plan

## Overview

Build the Angular login and register form UIs, connecting to the fully implemented backend auth layer. Both components are currently empty card stubs; the infrastructure (AuthService, interceptor, guard, routing) is already wired and working.

## Current State Analysis

The backend exposes four working auth endpoints (`/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`) with JWT tokens, BCrypt password encoding, and 22 passing integration tests. The Angular frontend has a complete auth infrastructure layer (`AuthService`, `TokenStorageService`, `AuthInterceptor` with auto-refresh on 401, `AuthGuard` with returnUrl). What's missing: `LoginComponent` and `RegisterComponent` are empty card stubs with no forms.

### Key Discoveries:

- `AuthService.login()` already calls `tokenStorage.setTokens()` internally via `tap()` — the component does not touch token storage directly (`auth.service.ts:24-26`)
- `AuthService.register()` returns `Observable<void>` — chain to `authService.login()` via `switchMap` for the auto-login flow (`auth.service.ts:19-21`)
- `AuthInterceptor` bypasses auth header injection AND 401 handling for `/auth/login`, `/auth/register`, `/auth/refresh` — 401 errors from the login endpoint reach the component's error handler directly
- `AuthGuard` redirects to `/login?returnUrl={originalUrl}` — LoginComponent must read this param and use it after success
- Backend password rules: 8+ chars, uppercase, digit, special char from `@#$%^&+=!?` — regex `/^(?=.*[A-Z])(?=.*\d)(?=.*[@#$%^&+=!?]).{8,}$/`
- Existing `.page-container` CSS (centered at `calc(100vh - 64px)`) is reused by both forms

## Desired End State

A user can navigate to `/register`, fill in email + password + confirm-password, submit, and land on `/dashboard` without any additional steps. A returning user can navigate to `/login`, enter credentials, submit, and land on `/dashboard` (or the original protected URL they were trying to reach). Both forms display inline error messages for validation failures and API errors.

## What We're NOT Doing

- No email verification flow (out of PRD scope for v0.1)
- No password reset flow (out of PRD scope for v0.1)
- No "remember me" beyond existing token TTLs
- No component-level unit tests (infrastructure tests cover guard/interceptor; component tests deferred)
- No changes to backend, routing, AuthService, interceptor, or guard

## Implementation Approach

Replace each stub's class and template in place — same file paths, same selector, same styles file. Both forms are standalone Angular components with reactive forms and Angular Material form fields. The register form chains `register()` into `login()` via `switchMap` for the auto-login flow.

## Critical Implementation Details

**`login()` stores tokens automatically**: `AuthService.login()` has a `tap()` that calls `tokenStorage.setTokens()`. The component subscribes to `authService.login()` and navigates on success — it does NOT call token storage directly.

**Auto-login chain in register**: Call `authService.register(payload).pipe(switchMap(() => authService.login(payload)))` and subscribe once. On next, navigate to `/dashboard`.

**401 on login reaches the component**: The interceptor bypasses `/auth/login`, so a wrong-credentials 401 is not swallowed by the auto-refresh logic — it propagates to the component's `error` handler as expected.

---

## Phase 1: Login form

### Overview

Replace the `LoginComponent` stub with a reactive form that authenticates via `AuthService.login()`, handles the returnUrl redirect, and shows an inline error on failure.

### Changes Required:

#### 1. Login component class

**File**: `src/main/frontend/src/app/features/login/login.component.ts`

**Intent**: Add a reactive form with email and password controls, a submit handler that calls `authService.login()`, a loading flag, an error message, and post-login navigation to `returnUrl` or `/dashboard`.

**Contract**:
- `imports: [ReactiveFormsModule, RouterLink, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule]`
- Form: `email` (Validators.required + Validators.email), `password` (Validators.required)
- `loading = false`, `errorMessage: string | null = null`, `showPassword = false`
- `returnUrl` read from `inject(ActivatedRoute).snapshot.queryParamMap.get('returnUrl') ?? '/dashboard'`
- On success: `router.navigateByUrl(returnUrl)`
- On error: status 401 → `errorMessage = 'Invalid email or password'`; otherwise → `errorMessage = 'Something went wrong. Please try again.'`

#### 2. Login component template

**File**: `src/main/frontend/src/app/features/login/login.component.html`

**Intent**: Form card with email + password fields, an inline error alert when `errorMessage` is non-null, a loading-aware submit button, and a "Need an account? Register" link.

**Contract**:
- Outer `<div class="page-container">` (existing) wraps `<mat-card class="auth-card">`
- `<div class="error-alert">` inside the card, above the form, visible only when `errorMessage` is set
- `mat-form-field` + `matInput type="email"` for email with "Required" / "Invalid email" `mat-error` messages
- `mat-form-field` + `matInput [type]="showPassword ? 'text' : 'password'"` for password; `mat-icon-button` suffix toggles `showPassword`; "Required" `mat-error`
- Submit `mat-flat-button`: `[disabled]="loginForm.invalid || loading"`; label "Sign in" normally, "Signing in…" when loading
- Footer: `<a routerLink="/register">Need an account? Register</a>`

#### 3. Login component styles

**File**: `src/main/frontend/src/app/features/login/login.component.scss`

**Intent**: Add `.auth-card` width constraint and `.error-alert` red banner styles to the existing centered layout.

**Contract**:
- `.auth-card`: `width: 100%; max-width: 400px`
- `.error-alert`: `background: #fdecea; border-left: 4px solid #f44336; padding: 12px 16px; border-radius: 4px; margin-bottom: 1rem; font-size: 0.875rem`

### Success Criteria:

#### Automated Verification:

- Angular app compiles cleanly: `cd src/main/frontend && ng build`
- Existing tests still pass: `cd src/main/frontend && ng test --watch=false`

#### Manual Verification:

- Navigate to `/login`; submit valid credentials → redirected to `/dashboard`
- Submit wrong credentials → inline "Invalid email or password" appears; no page reload
- Navigate to `/dashboard` while unauthenticated → redirected to `/login?returnUrl=/dashboard` → sign in → land on `/dashboard`
- Password show/hide toggle works
- "Need an account? Register" link navigates to `/register`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Phase 2: Register form

### Overview

Replace the `RegisterComponent` stub with a reactive form that registers a new user, auto-logs in, and redirects to `/dashboard`. Includes a confirm-password field with a cross-field validator and full password complexity validation matching the backend rules.

### Changes Required:

#### 1. Register component class

**File**: `src/main/frontend/src/app/features/register/register.component.ts`

**Intent**: Reactive form with email, password (custom complexity validator), and confirmPassword (group-level match validator); submit handler chains `register()` into `login()` via `switchMap`; inline error on API failure; navigate to `/dashboard` on success.

**Contract**:
- Same imports as `LoginComponent`
- `passwordComplexityValidator: ValidatorFn` returns `{ passwordComplexity: true }` when `/^(?=.*[A-Z])(?=.*\d)(?=.*[@#$%^&+=!?]).{8,}$/` does not match
- `passwordMatchValidator: ValidatorFn` applied at group level — returns `{ passwordMismatch: true }` when `password.value !== confirmPassword.value`
- Submit: `authService.register(payload).pipe(switchMap(() => authService.login(payload)))` — single subscription; on next navigate to `/dashboard`
- On HTTP 409: `errorMessage = 'This email is already registered'`; on other errors: `errorMessage = 'Registration failed. Please try again.'`

#### 2. Register component template

**File**: `src/main/frontend/src/app/features/register/register.component.html`

**Intent**: Form card with email, password, and confirm-password fields; inline error alert; loading-aware submit button; "Already have an account?" footer link.

**Contract**:
- Same `.page-container` + `.auth-card` + `.error-alert` structure as login
- Password field shows separate `mat-error` entries per failing rule: "At least 8 characters", "Requires an uppercase letter", "Requires a digit", "Requires a special character (@#$%^&+=!?)"
- Confirm-password `mat-error` "Passwords do not match" shown when `registerForm.hasError('passwordMismatch') && confirmPasswordControl.touched`
- Submit button: `[disabled]="registerForm.invalid || loading"`; label "Create account" / "Creating account…" when loading
- Footer: `<a routerLink="/login">Already have an account? Sign in</a>`

#### 3. Register component styles

**File**: `src/main/frontend/src/app/features/register/register.component.scss`

**Intent**: Same `.auth-card` and `.error-alert` styles as the login form — each component is standalone so styles are duplicated rather than extracted to a shared file.

### Success Criteria:

#### Automated Verification:

- Angular app compiles cleanly: `cd src/main/frontend && ng build`
- Existing tests still pass: `cd src/main/frontend && ng test --watch=false`

#### Manual Verification:

- Navigate to `/register`; mismatched confirm-password → "Passwords do not match" shown before any API call
- Weak password (e.g. all-lowercase) → per-rule `mat-error` messages shown for each failing rule
- Valid form (unique email, strong password, matching confirm) → auto-logged in → redirected to `/dashboard`
- Already-registered email → inline "This email is already registered"
- Password show/hide toggle works
- "Already have an account? Sign in" link navigates to `/login`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that manual testing was successful. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes live in the `## Progress` section at the bottom of the plan.

---

## Testing Strategy

### Manual Testing Steps:
1. Start the backend locally: `./mvnw spring-boot:run --spring.profiles.active=local`
2. Start the Angular dev server: `cd src/main/frontend && ng serve`
3. Register a new account → verify auto-login and dashboard redirect
4. Log out via toolbar → log in with same credentials → verify dashboard
5. Try registering with the same email → verify "already registered" error
6. Try logging in with the wrong password → verify inline error

## References

- Backend auth endpoints: `src/main/java/com/example/finance_hq/auth/AuthController.java`
- Auth infrastructure: `src/main/frontend/src/app/core/auth/`
- AuthService: `src/main/frontend/src/app/core/auth/auth.service.ts`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Login form

#### Automated

- [x] 1.1 Angular app compiles cleanly: `cd src/main/frontend && ng build`
- [x] 1.2 Existing tests still pass: `cd src/main/frontend && ng test --watch=false`

#### Manual

- [ ] 1.3 Submit valid credentials → redirected to /dashboard
- [ ] 1.4 Submit wrong credentials → inline "Invalid email or password" error appears
- [ ] 1.5 Unauthenticated /dashboard access → redirect to /login?returnUrl → sign in → land on /dashboard
- [ ] 1.6 Password show/hide toggle works
- [ ] 1.7 "Need an account? Register" link navigates to /register

### Phase 2: Register form

#### Automated

- [ ] 2.1 Angular app compiles cleanly: `cd src/main/frontend && ng build`
- [ ] 2.2 Existing tests still pass: `cd src/main/frontend && ng test --watch=false`

#### Manual

- [ ] 2.3 Mismatched confirm-password → "Passwords do not match" shown before API call
- [ ] 2.4 Weak password → per-rule mat-errors shown
- [ ] 2.5 Valid form → auto-logged in → redirected to /dashboard
- [ ] 2.6 Existing email → inline "This email is already registered"
- [ ] 2.7 Password show/hide toggle works
- [ ] 2.8 "Already have an account? Sign in" link navigates to /login
