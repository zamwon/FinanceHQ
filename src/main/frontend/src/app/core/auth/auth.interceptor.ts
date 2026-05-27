import { HttpInterceptorFn, HttpErrorResponse, HttpRequest, HttpHandlerFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { BehaviorSubject, throwError, Observable } from 'rxjs';
import { catchError, filter, switchMap, take } from 'rxjs/operators';
import { TokenStorageService } from './token-storage.service';
import { AuthService } from './auth.service';

const AUTH_BYPASS_PATHS = ['/auth/login', '/auth/register', '/auth/refresh'];
const refresh$ = new BehaviorSubject<string | null>(null);
let refreshInFlight = false;

function addBearer(req: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const tokenStorage = inject(TokenStorageService);
  const authService = inject(AuthService);
  const router = inject(Router);

  if (AUTH_BYPASS_PATHS.some(p => req.url.includes(p))) {
    return next(req);
  }

  const access = tokenStorage.getAccessToken();
  const outgoing = access ? addBearer(req, access) : req;

  return next(outgoing).pipe(
    catchError(err => {
      if (!(err instanceof HttpErrorResponse) || err.status !== 401) {
        return throwError(() => err);
      }

      if (req.url.includes('/auth/refresh')) {
        tokenStorage.clear();
        router.navigate(['/login']);
        return throwError(() => err);
      }

      if (refreshInFlight) {
        return refresh$.pipe(
          filter((t): t is string => !!t),
          take(1),
          switchMap(newToken => next(addBearer(req, newToken)))
        );
      }

      refreshInFlight = true;
      refresh$.next(null);
      const storedRefresh = tokenStorage.getRefreshToken();
      if (!storedRefresh) {
        refreshInFlight = false;
        tokenStorage.clear();
        router.navigate(['/login']);
        return throwError(() => err);
      }

      return authService.refresh(storedRefresh).pipe(
        switchMap(res => {
          refreshInFlight = false;
          refresh$.next(res.accessToken);
          return next(addBearer(req, res.accessToken));
        }),
        catchError(refreshErr => {
          refreshInFlight = false;
          tokenStorage.clear();
          router.navigate(['/login']);
          return throwError(() => refreshErr);
        })
      );
    })
  );
};
