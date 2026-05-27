import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, MaybeAsync, GuardResult } from '@angular/router';
import { provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { authGuard } from './auth.guard';
import { TokenStorageService } from './token-storage.service';

function runGuard(url: string): MaybeAsync<GuardResult> {
  const routeSnapshot = {} as ActivatedRouteSnapshot;
  const stateSnapshot = { url } as RouterStateSnapshot;
  return TestBed.runInInjectionContext(() => authGuard(routeSnapshot, stateSnapshot));
}

describe('authGuard', () => {
  let getAccessToken: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    getAccessToken = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: TokenStorageService,
          useValue: { getAccessToken },
        },
      ],
    });
  });

  it('redirects to /login when no token', () => {
    getAccessToken.mockReturnValue(null);

    const result = runGuard('/dashboard');

    expect(result).toBeInstanceOf(UrlTree);
    const tree = result as UrlTree;
    expect(tree.toString()).toContain('/login');
    expect(tree.toString()).toContain('returnUrl=%2Fdashboard');
  });

  it('allows activation when token is present', () => {
    getAccessToken.mockReturnValue('fake');

    const result = runGuard('/dashboard');

    expect(result).toBe(true);
  });
});
