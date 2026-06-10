import { Component, Input, Output, EventEmitter, inject, signal, DestroyRef } from '@angular/core';
import { TitleCasePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Transaction } from '../transaction.model';
import { TransactionsService } from '../transactions.service';

@Component({
  standalone: true,
  selector: 'app-transaction-delete-dialog',
  imports: [TitleCasePipe],
  templateUrl: './delete-dialog.component.html',
})
export class TransactionDeleteDialogComponent {
  @Input({ required: true }) transaction!: Transaction;
  @Output() deleted = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  private svc = inject(TransactionsService);
  private destroyRef = inject(DestroyRef);
  loading = signal(false);
  error = signal('');

  confirm(): void {
    this.loading.set(true);
    this.error.set('');
    this.svc.delete(this.transaction.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.deleted.emit(),
        error: () => { this.loading.set(false); this.error.set('Failed to delete. Please try again.'); },
      });
  }
}
