import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PortfolioAsset, CreatePortfolioAssetDto, UpdatePortfolioAssetDto, PriceRefreshResponse } from './portfolio.model';

const BASE = '/api/portfolio';

@Injectable({ providedIn: 'root' })
export class PortfolioService {
  private http = inject(HttpClient);

  getAll(): Observable<PortfolioAsset[]> {
    return this.http.get<PortfolioAsset[]>(BASE);
  }

  create(dto: CreatePortfolioAssetDto): Observable<PortfolioAsset> {
    return this.http.post<PortfolioAsset>(BASE, dto);
  }

  update(id: string, dto: UpdatePortfolioAssetDto): Observable<PortfolioAsset> {
    return this.http.patch<PortfolioAsset>(`${BASE}/${id}`, dto);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${id}`);
  }

  refreshPrices(): Observable<PriceRefreshResponse> {
    return this.http.post<PriceRefreshResponse>(`${BASE}/refresh-prices`, null);
  }

  importCsv(file: File): Observable<{ importedCount: number }> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ importedCount: number }>(`${BASE}/import`, form);
  }
}
