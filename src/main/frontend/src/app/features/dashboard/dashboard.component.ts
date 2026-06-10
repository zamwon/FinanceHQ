import { Component, inject, signal, OnInit, computed } from '@angular/core';
import { BaseChartDirective } from 'ng2-charts';
import { ChartData, ChartOptions } from 'chart.js';
import { DashboardService } from './dashboard.service';
import { MonthlySummaryResponse, MonthlyTrendItem } from './dashboard.model';

@Component({
  selector: 'app-dashboard',
  imports: [BaseChartDirective],
  templateUrl: './dashboard.component.html',
})
export class DashboardComponent implements OnInit {
  private svc = inject(DashboardService);

  summary = signal<MonthlySummaryResponse | null>(null);
  trends = signal<MonthlyTrendItem[]>([]);
  loadingSummary = signal(true);
  loadingTrends = signal(false);
  error = signal('');
  trendsVisible = signal(false);
  trendsLoaded = signal(false);
  selectedMonth = signal(this.currentMonth());

  chartData = computed<ChartData<'bar'>>(() => {
    const t = this.trends();
    return {
      labels: t.map(i => i.month),
      datasets: [
        {
          label: 'Income',
          data: t.map(i => Number(i.totalIncome)),
          backgroundColor: 'rgba(34, 197, 94, 0.6)',
          borderColor: 'rgba(34, 197, 94, 1)',
          borderWidth: 1,
        },
        {
          label: 'Expenses',
          data: t.map(i => Number(i.totalExpenses)),
          backgroundColor: 'rgba(239, 68, 68, 0.6)',
          borderColor: 'rgba(239, 68, 68, 1)',
          borderWidth: 1,
        },
      ],
    };
  });

  chartOptions: ChartOptions<'bar'> = {
    responsive: true,
    maintainAspectRatio: false,
    scales: { y: { beginAtZero: true } },
    plugins: { legend: { position: 'top' } },
  };

  ngOnInit(): void {
    this.loadSummary();
  }

  loadSummary(): void {
    this.loadingSummary.set(true);
    this.error.set('');
    this.svc.getSummary(this.selectedMonth()).subscribe({
      next: data => { this.summary.set(data); this.loadingSummary.set(false); },
      error: () => { this.error.set('Failed to load summary.'); this.loadingSummary.set(false); },
    });
  }

  onMonthChange(event: Event): void {
    this.selectedMonth.set((event.target as HTMLInputElement).value);
    this.loadSummary();
  }

  toggleTrends(): void {
    const nowVisible = !this.trendsVisible();
    this.trendsVisible.set(nowVisible);
    if (nowVisible && !this.trendsLoaded()) {
      this.loadingTrends.set(true);
      this.svc.getTrends(6).subscribe({
        next: data => { this.trends.set(data); this.trendsLoaded.set(true); this.loadingTrends.set(false); },
        error: () => { this.error.set('Failed to load trends.'); this.loadingTrends.set(false); },
      });
    }
  }

  formatAmount(amount: number | undefined | null): string {
    return (amount ?? 0).toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  netBalanceClass(amount: number | undefined | null): string {
    const n = amount ?? 0;
    if (n > 0) return 'text-green-600 dark:text-green-400';
    if (n < 0) return 'text-red-600 dark:text-red-400';
    return 'text-zinc-900 dark:text-zinc-50';
  }

  private currentMonth(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }
}
