# Register and Login — Plan Brief

> Full plan: `context/changes/register-and-login/plan.md`

## What & Why

Build the Angular login and register form UIs. The backend auth layer (register, login, refresh, logout endpoints) and all Angular infrastructure (AuthService, interceptor, guard, routing, token storage) are already implemented and tested — only the visible form components are missing.

## Starting Point

`LoginComponent` and `RegisterComponent` are empty card stubs with placeholder text and no forms. The Angular infrastructure layer (AuthService, AuthInterceptor with auto-refresh, AuthGuard with returnUrl) is fully wired and working.

## Desired End State

A new user visits `/register`, fills in email + password + confirm-password, submits, and lands on `/dashboard` automatically logged in. A returning user visits `/login`, enters credentials, and lands on `/dashboard` (or the page they originally tried to reach). Both forms show inline errors for bad inputs and API failures.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Post-register flow | Auto-login then redirect to /dashboard | Zero extra step — best UX, avoids asking user to log in twice | Plan |
| Error display | Inline alert inside the form card | Co-located with the form; errors are persistent and won't be missed | Plan |
| Confirm-password field | Yes, required on register form | A mistyped password locks the user out with no recovery path (no reset in v0.1) | Plan |
| Cross-navigation | Yes — each form links to the other | Standard auth UX convention; prevents dead ends | Plan |
| Client-side password validation | Match backend rules exactly (8+, uppercase, digit, special) | Consistent feedback before the API call; same baseline already enforced server-side | Plan |

## Scope

**In scope:**
- `LoginComponent` — reactive form, inline errors, returnUrl redirect, show/hide toggle
- `RegisterComponent` — reactive form, confirm-password, complexity validator, auto-login chain, inline errors

**Out of scope:**
- Email verification, password reset, "remember me"
- Component-level unit tests
- Any backend or infrastructure changes

## Architecture / Approach

Both components are standalone Angular components. Each replaces its existing stub in place (same file paths). Reactive forms with Angular Material form fields. Register uses `switchMap` to chain `register()` into `login()` so the user never sees a "now please log in" step. The custom `passwordComplexityValidator` mirrors the backend regex (`/^(?=.*[A-Z])(?=.*\d)(?=.*[@#$%^&+=!?]).{8,}$/`) so client-side errors appear before the network call.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Login form | Working /login page with validation, returnUrl support, inline errors | Angular Material form field imports — first use in any feature component |
| 2. Register form | Working /register page with confirm-password, auto-login chain, inline errors | `switchMap` chain: if `register()` succeeds but `login()` fails, user is registered but not logged in — show "Registration succeeded, please sign in" fallback |

**Prerequisites:** Backend running locally with `--spring.profiles.active=local`; `JWT_SECRET` set in `application-local.yml`
**Estimated effort:** ~1 session, 2 phases

## Open Risks & Assumptions

- If `authService.register()` succeeds but `authService.login()` immediately fails (e.g. network error between the two calls), the user is registered but never redirected. Handle this in the register error handler: catch the login failure and navigate to `/login?registered=true` with a "Account created — please sign in" message.
- Angular Material form field imports have not been used in any feature component yet; check that `MatFormFieldModule` and `MatInputModule` are available in the project without additional install.

## Success Criteria (Summary)

- Register with a new email → lands on `/dashboard` in a single flow with no extra steps
- Login with correct credentials → lands on `/dashboard` (or returnUrl)
- Invalid inputs and API errors surface as inline messages inside the form card
