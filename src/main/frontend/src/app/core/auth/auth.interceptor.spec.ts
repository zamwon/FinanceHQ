import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { Subject, of } from 'rxjs';
import { TokenStorageService } from './token-storage.service';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';

const TOKEN_RESPONSE = {
  accessToken: 'new-access-token',
  refreshToken: 'new-refresh-token',
  tokenType: 'Bearer' as const,
  expiresIn: 3600,
};

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpController: HttpTestingController;
  let mockRouter: { navigate: ReturnType<typeof vi.fn> };
  let mockTokenStorage: {
    getAccessToken: ReturnType<typeof vi.fn>;
    getRefreshToken: ReturnType<typeof vi.fn>;
    setTokens: ReturnType<typeof vi.fn>;
    clear: ReturnType<typeof vi.fn>;
  };
  let mockAuthService: { refresh: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    mockRouter = { navigate: vi.fn() };
    mockTokenStorage = {
      getAccessToken: vi.fn().mockReturnValue('old-access-token'),
      getRefreshToken: vi.fn().mockReturnValue('stored-refresh-token'),
      setTokens: vi.fn(),
      clear: vi.fn(),
    };
    mockAuthService = { refresh: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: mockRouter },
        { provide: TokenStorageService, useValue: mockTokenStorage },
        { provide: AuthService, useValue: mockAuthService },
      ],
    });

    http = TestBed.inject(HttpClient);
    httpController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpController.verify());

  it('single 401 - refresh succeeds - retries with new token', () => {
    mockAuthService.refresh.mockReturnValue(of(TOKEN_RESPONSE));

    let result: unknown;
    http.get('/api/test').subscribe(r => { result = r; });

    // Original request — flush 401
    httpController.expectOne('/api/test').flush({}, { status: 401, statusText: 'Unauthorized' });

    // Interceptor should retry with new access token
    const retry = httpController.expectOne('/api/test');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new-access-token');
    retry.flush({ data: 'ok' });

    expect(result).toEqual({ data: 'ok' });
    expect(mockAuthService.refresh).toHaveBeenCalledTimes(1);
    expect(mockAuthService.refresh).toHaveBeenCalledWith('stored-refresh-token');
  });

  it('concurrent 401s - refresh succeeds - single refresh call, both requests retry', () => {
    // Use a Subject so the refresh call is async — both 401s arrive before refresh resolves
    const refreshSubject = new Subject<typeof TOKEN_RESPONSE>();
    mockAuthService.refresh.mockReturnValue(refreshSubject.asObservable());

    const resultsA: unknown[] = [];
    const resultsB: unknown[] = [];

    http.get('/api/a').subscribe(r => resultsA.push(r));
    http.get('/api/b').subscribe(r => resultsB.push(r));

    // Both 401s arrive before refresh resolves
    httpController.expectOne('/api/a').flush({}, { status: 401, statusText: 'Unauthorized' });
    httpController.expectOne('/api/b').flush({}, { status: 401, statusText: 'Unauthorized' });

    // Only one refresh call should have been made
    expect(mockAuthService.refresh).toHaveBeenCalledTimes(1);

    // Refresh resolves — both waiting requests should retry
    refreshSubject.next(TOKEN_RESPONSE);
    refreshSubject.complete();

    const retryA = httpController.expectOne('/api/a');
    const retryB = httpController.expectOne('/api/b');
    expect(retryA.request.headers.get('Authorization')).toBe('Bearer new-access-token');
    expect(retryB.request.headers.get('Authorization')).toBe('Bearer new-access-token');
    retryA.flush({ data: 'a' });
    retryB.flush({ data: 'b' });

    expect(resultsA).toEqual([{ data: 'a' }]);
    expect(resultsB).toEqual([{ data: 'b' }]);
  });

  it('concurrent 401s - refresh fails - both requests receive error (not hang)', () => {
    const refreshSubject = new Subject<typeof TOKEN_RESPONSE>();
    mockAuthService.refresh.mockReturnValue(refreshSubject.asObservable());

    const errors: unknown[] = [];

    http.get('/api/a').subscribe({ next: vi.fn(), error: e => errors.push(e) });
    http.get('/api/b').subscribe({ next: vi.fn(), error: e => errors.push(e) });

    httpController.expectOne('/api/a').flush({}, { status: 401, statusText: 'Unauthorized' });
    httpController.expectOne('/api/b').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(mockAuthService.refresh).toHaveBeenCalledTimes(1);

    // Refresh fails — queued request (/api/b) must also fail, not hang
    refreshSubject.error(new Error('refresh failed'));

    // Expected RED: current code never calls refresh$.error(), so /api/b hangs
    // After fix: refresh$.error() signals /api/b, both errors arrive
    expect(errors).toHaveLength(2);
    expect(mockTokenStorage.clear).toHaveBeenCalled();
    expect(mockRouter.navigate).toHaveBeenCalledWith(['/login']);
  });
});
