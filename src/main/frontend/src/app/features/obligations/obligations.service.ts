import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Obligation, CreateObligationDto, UpdateObligationDto } from './obligation.model';
import { Transaction } from '../transactions/transaction.model';

const BASE = '/api/obligations';

@Injectable({ providedIn: 'root' })
export class ObligationsService {
  private http = inject(HttpClient);

  getAll(): Observable<Obligation[]> {
    return this.http.get<Obligation[]>(BASE);
  }

  create(dto: CreateObligationDto): Observable<Obligation> {
    return this.http.post<Obligation>(BASE, dto);
  }

  update(id: string, dto: UpdateObligationDto): Observable<Obligation> {
    return this.http.patch<Obligation>(`${BASE}/${id}`, dto);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${id}`);
  }

  markPaid(id: string, dto: object): Observable<Transaction> {
    return this.http.post<Transaction>(`${BASE}/${id}/pay`, dto);
  }
}
