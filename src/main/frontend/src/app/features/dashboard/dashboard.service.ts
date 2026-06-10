import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { MonthlySummaryResponse, MonthlyTrendItem } from './dashboard.model';

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private http = inject(HttpClient);
  private base = '/api/dashboard';

  getSummary(month?: string): Observable<MonthlySummaryResponse> {
    const params = month ? new HttpParams().set('month', month) : undefined;
    return this.http.get<MonthlySummaryResponse>(`${this.base}/summary`, { params });
  }

  getTrends(months: number): Observable<MonthlyTrendItem[]> {
    const params = new HttpParams().set('months', months.toString());
    return this.http.get<MonthlyTrendItem[]>(`${this.base}/trends`, { params });
  }
}
