import { Component, inject, signal, OnInit, ElementRef, ViewChild } from '@angular/core';
import { PortfolioService } from './portfolio.service';
import { PortfolioAsset } from './portfolio.model';
import { PortfolioDialogComponent } from './portfolio-dialog/portfolio-dialog.component';
import { PortfolioDeleteDialogComponent } from './delete-dialog/delete-dialog.component';
import { ToastService } from '../../shared/ui/toast/toast.service';

@Component({
  selector: 'app-portfolio',
  imports: [PortfolioDialogComponent, PortfolioDeleteDialogComponent],
  templateUrl: './portfolio.component.html',
})
export class PortfolioComponent implements OnInit {
  @ViewChild('csvInput') csvInput!: ElementRef<HTMLInputElement>;

  private svc = inject(PortfolioService);
  private toast = inject(ToastService);

  assets = signal<PortfolioAsset[]>([]);
  loading = signal(true);
  error = signal('');
  importErrors = signal<{ rowNumber: number; column: string; message: string }[]>([]);

  showAddEdit = signal(false);
  editingAsset = signal<PortfolioAsset | null>(null);
  showDelete = signal(false);
  deletingAsset = signal<PortfolioAsset | null>(null);

  ngOnInit(): void {
    this.loadPortfolio();
  }

  loadPortfolio(): void {
    this.loading.set(true);
    this.svc.refreshPrices().subscribe({
      next: (res) => {
        this.assets.set(res.assets);
        this.loading.set(false);
        if (res.refreshed) {
          this.toast.show('Prices refreshed.');
        }
      },
      error: () => {
        this.error.set('Failed to load portfolio.');
        this.loading.set(false);
      },
    });
  }

  openAdd(): void {
    this.editingAsset.set(null);
    this.showAddEdit.set(true);
  }

  openEdit(a: PortfolioAsset): void {
    this.editingAsset.set(a);
    this.showAddEdit.set(true);
  }

  openDelete(a: PortfolioAsset): void {
    this.deletingAsset.set(a);
    this.showDelete.set(true);
  }

  onSaved(): void {
    const wasEdit = this.editingAsset() !== null;
    this.showAddEdit.set(false);
    this.toast.show(wasEdit ? 'Position updated.' : 'Position added.');
    this.loadPortfolio();
  }

  onDeleted(): void {
    this.showDelete.set(false);
    this.toast.show('Position deleted.');
    this.loadPortfolio();
  }

  triggerCsvImport(): void {
    this.csvInput.nativeElement.value = '';
    this.csvInput.nativeElement.click();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.importErrors.set([]);

    this.svc.importCsv(file).subscribe({
      next: (res) => {
        this.toast.show(`Imported ${res.importedCount} position${res.importedCount === 1 ? '' : 's'}.`);
        this.loadPortfolio();
      },
      error: (err) => {
        if (err?.status === 422 && err?.error?.rowErrors) {
          this.importErrors.set(err.error.rowErrors);
        } else {
          const detail = err?.error?.detail ?? 'CSV import failed.';
          this.toast.show(detail, 3000, true);
        }
      },
    });
  }

  isPriceStale(asset: PortfolioAsset): boolean {
    if (!asset.priceLastUpdatedAt) return false;
    const updated = new Date(asset.priceLastUpdatedAt).getTime();
    return Date.now() - updated > 25 * 60 * 60 * 1000;
  }

  formatRelativeTime(isoString: string | null): string {
    if (!isoString) return '—';
    const diff = Date.now() - new Date(isoString).getTime();
    const hours = Math.floor(diff / 3600000);
    const minutes = Math.floor((diff % 3600000) / 60000);
    if (hours > 0) return `${hours}h ago`;
    if (minutes > 0) return `${minutes}m ago`;
    return 'just now';
  }

  formatNumber(val: number | null, decimals = 2): string {
    if (val === null || val === undefined) return '—';
    return val.toLocaleString('pl-PL', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
  }
}
