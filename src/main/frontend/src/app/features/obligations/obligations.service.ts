import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Obligation, CreateObligationDto, UpdateObligationDto } from './obligation.model';

@Injectable({ providedIn: 'root' })
export class ObligationsService {
  private http = inject(HttpClient);
  private base = '/api/obligations';

  getAll(): Observable<Obligation[]> {
    return this.http.get<Obligation[]>(this.base);
  }

  create(dto: CreateObligationDto): Observable<Obligation> {
    return this.http.post<Obligation>(this.base, dto);
  }

  update(id: string, dto: UpdateObligationDto): Observable<Obligation> {
    return this.http.patch<Obligation>(`${this.base}/${id}`, dto);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
