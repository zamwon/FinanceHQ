import { Component, Input, Output, EventEmitter, OnInit, inject, signal, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PortfolioAsset } from '../portfolio.model';
import { PortfolioService } from '../portfolio.service';

@Component({
  standalone: true,
  selector: 'app-portfolio-dialog',
  imports: [ReactiveFormsModule],
  templateUrl: './portfolio-dialog.component.html',
})
export class PortfolioDialogComponent implements OnInit {
  @Input() asset: PortfolioAsset | null = null;
  @Output() saved = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private svc = inject(PortfolioService);
  private destroyRef = inject(DestroyRef);

  loading = signal(false);
  error = signal('');

  form = this.fb.group({
    ticker: ['', [Validators.required, Validators.maxLength(200)]],
    assetGroup: ['', [Validators.required, Validators.maxLength(100)]],
    shares: [null as number | null, [Validators.required, Validators.min(0.00000001)]],
    avgBuyPricePln: [null as number | null, [Validators.required, Validators.min(0.00000001)]],
    avgBuyPriceAssetCurrency: [null as number | null, [Validators.required, Validators.min(0.00000001)]],
    purchaseValuePln: [null as number | null, [Validators.required, Validators.min(0.00000001)]],
    purchaseValueAssetCurrency: [null as number | null, [Validators.required, Validators.min(0.00000001)]],
    purchaseSharePercent: [null as number | null, [Validators.min(0), Validators.max(100)]],
  });

  get isEdit(): boolean { return this.asset !== null; }

  ngOnInit(): void {
    if (this.asset) {
      const a = this.asset;
      this.form.patchValue({
        ticker: a.ticker,
        assetGroup: a.assetGroup,
        shares: a.shares,
        avgBuyPricePln: a.avgBuyPricePln,
        avgBuyPriceAssetCurrency: a.avgBuyPriceAssetCurrency,
        purchaseValuePln: a.purchaseValuePln,
        purchaseValueAssetCurrency: a.purchaseValueAssetCurrency,
        purchaseSharePercent: a.purchaseSharePercent,
      });
    }
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set('');
    const v = this.form.getRawValue();

    const call = this.isEdit
      ? this.svc.update(this.asset!.id, {
          ticker: v.ticker ?? undefined,
          assetGroup: v.assetGroup ?? undefined,
          shares: v.shares ?? undefined,
          avgBuyPricePln: v.avgBuyPricePln ?? undefined,
          avgBuyPriceAssetCurrency: v.avgBuyPriceAssetCurrency ?? undefined,
          purchaseValuePln: v.purchaseValuePln ?? undefined,
          purchaseValueAssetCurrency: v.purchaseValueAssetCurrency ?? undefined,
          purchaseSharePercent: v.purchaseSharePercent ?? undefined,
        })
      : this.svc.create({
          ticker: v.ticker!,
          assetGroup: v.assetGroup!,
          shares: v.shares!,
          avgBuyPricePln: v.avgBuyPricePln!,
          avgBuyPriceAssetCurrency: v.avgBuyPriceAssetCurrency!,
          purchaseValuePln: v.purchaseValuePln!,
          purchaseValueAssetCurrency: v.purchaseValueAssetCurrency!,
          purchaseSharePercent: v.purchaseSharePercent,
        });

    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.loading.set(false); this.saved.emit(); },
      error: (err) => {
        this.loading.set(false);
        const detail = err?.error?.detail;
        this.error.set(detail ?? 'Failed to save. Please try again.');
      },
    });
  }
}
