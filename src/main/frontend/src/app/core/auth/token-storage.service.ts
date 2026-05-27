import { Injectable } from '@angular/core';

const ACCESS_KEY = 'fhq.accessToken';
const REFRESH_KEY = 'fhq.refreshToken';

@Injectable({ providedIn: 'root' })
export class TokenStorageService {
  setTokens(accessToken: string, refreshToken: string): void {
    localStorage.setItem(ACCESS_KEY, accessToken);
    localStorage.setItem(REFRESH_KEY, refreshToken);
  }

  getAccessToken(): string | null {
    return localStorage.getItem(ACCESS_KEY);
  }

  getRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_KEY);
  }

  clear(): void {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  }
}
