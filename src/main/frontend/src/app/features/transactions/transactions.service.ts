import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Transaction } from './transaction.model';

const BASE = '/api/transactions';

@Injectable({ providedIn: 'root' })
export class TransactionsService {
  private http = inject(HttpClient);

  getAll(): Observable<Transaction[]> {
    return this.http.get<Transaction[]>(BASE);
  }

  create(dto: object): Observable<Transaction> {
    return this.http.post<Transaction>(BASE, dto);
  }

  update(id: string, dto: object): Observable<Transaction> {
    return this.http.patch<Transaction>(`${BASE}/${id}`, dto);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${id}`);
  }
}
