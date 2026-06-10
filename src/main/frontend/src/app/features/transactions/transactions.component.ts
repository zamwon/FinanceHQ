import { Component, inject, signal, OnInit } from '@angular/core';
import { TransactionsService } from './transactions.service';
import { Transaction } from './transaction.model';
import { TransactionDialogComponent } from './transaction-dialog/transaction-dialog.component';
import { TransactionDeleteDialogComponent } from './delete-dialog/delete-dialog.component';
import { ToastService } from '../../shared/ui/toast/toast.service';

@Component({
  selector: 'app-transactions',
  imports: [TransactionDialogComponent, TransactionDeleteDialogComponent],
  templateUrl: './transactions.component.html',
})
export class TransactionsComponent implements OnInit {
  private svc = inject(TransactionsService);
  private toast = inject(ToastService);

  transactions = signal<Transaction[]>([]);
  loading = signal(true);
  error = signal('');
  showAddEdit = signal(false);
  showDelete = signal(false);
  editingTransaction = signal<Transaction | null>(null);
  deletingTransaction = signal<Transaction | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.svc.getAll().subscribe({
      next: (data) => { this.transactions.set(data); this.loading.set(false); },
      error: () => { this.error.set('Failed to load transactions.'); this.loading.set(false); },
    });
  }

  openAdd(): void {
    this.editingTransaction.set(null);
    this.showAddEdit.set(true);
  }

  openEdit(t: Transaction): void {
    this.editingTransaction.set(t);
    this.showAddEdit.set(true);
  }

  openDelete(t: Transaction): void {
    this.deletingTransaction.set(t);
    this.showDelete.set(true);
  }

  onSaved(): void {
    const wasEdit = this.editingTransaction() !== null;
    this.showAddEdit.set(false);
    this.toast.show(wasEdit ? 'Transaction updated.' : 'Transaction added.');
    this.load();
  }

  onDeleted(): void {
    this.showDelete.set(false);
    this.toast.show('Transaction deleted.');
    this.load();
  }

  displayDate(t: Transaction): string {
    return t.period ? (t.nextExpectedDate ?? '—') : (t.date ?? '—');
  }

  dateLabel(t: Transaction): string {
    return t.period ? 'Next expected' : 'Date';
  }
}
