import { CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { TokenStorageService } from './token-storage.service';

export const authGuard: CanActivateFn = (route, state) => {
  const tokenStorage = inject(TokenStorageService);
  const router = inject(Router);

  if (tokenStorage.getAccessToken()) {
    return true;
  }

  return router.parseUrl(`/login?returnUrl=${encodeURIComponent(state.url)}`);
};
