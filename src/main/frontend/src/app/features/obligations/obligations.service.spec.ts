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
