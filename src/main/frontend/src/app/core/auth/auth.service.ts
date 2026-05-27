import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { tap, finalize } from 'rxjs/operators';
import { TokenStorageService } from './token-storage.service';

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private tokenStorage = inject(TokenStorageService);

  register(req: { email: string; password: string }): Observable<void> {
    return this.http.post<void>('/auth/register', req);
  }

  login(req: { email: string; password: string }): Observable<TokenResponse> {
    return this.http.post<TokenResponse>('/auth/login', req).pipe(
      tap(res => this.tokenStorage.setTokens(res.accessToken, res.refreshToken))
    );
  }

  refresh(refreshToken: string): Observable<TokenResponse> {
    return this.http.post<TokenResponse>('/auth/refresh', { refreshToken }).pipe(
      tap(res => this.tokenStorage.setTokens(res.accessToken, res.refreshToken))
    );
  }

  logout(): Observable<void> {
    const refreshToken = this.tokenStorage.getRefreshToken();
    return this.http.post<void>('/auth/logout', { refreshToken }).pipe(
      finalize(() => this.tokenStorage.clear())
    );
  }

  isAuthenticated(): boolean {
    return !!this.tokenStorage.getAccessToken();
  }
}
