import { Component, inject, signal, OnInit } from '@angular/core';
import { ObligationsService } from './obligations.service';
import { Obligation } from './obligation.model';
import { CategoryBadgeComponent } from '../../shared/ui/category-badge/category-badge.component';
import { ObligationDialogComponent } from './obligation-dialog/obligation-dialog.component';
import { DeleteDialogComponent } from './delete-dialog/delete-dialog.component';
import { ToastService } from '../../shared/ui/toast/toast.service';

@Component({
  selector: 'app-obligations',
  imports: [CategoryBadgeComponent, ObligationDialogComponent, DeleteDialogComponent],
  templateUrl: './obligations.component.html',
})
export class ObligationsComponent implements OnInit {
  private svc = inject(ObligationsService);
  private toast = inject(ToastService);

  obligations = signal<Obligation[]>([]);
  loading = signal(true);
  error = signal('');

  // Dialog state (dialogs added in Tasks 12 and 13)
  showAddEdit = signal(false);
  showDelete = signal(false);
  editing = signal<Obligation | null>(null);
  deleting = signal<Obligation | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.svc.getAll().subscribe({
      next: (data) => { this.obligations.set(data); this.loading.set(false); },
      error: () => { this.error.set('Failed to load obligations.'); this.loading.set(false); },
    });
  }

  openAdd(): void {
    this.editing.set(null);
    this.showAddEdit.set(true);
  }

  openEdit(o: Obligation): void {
    this.editing.set(o);
    this.showAddEdit.set(true);
  }

  openDelete(o: Obligation): void {
    this.deleting.set(o);
    this.showDelete.set(true);
  }

  onSaved(): void {
    const wasEdit = this.editing() !== null;
    this.showAddEdit.set(false);
    this.toast.show(wasEdit ? 'Obligation updated.' : 'Obligation added.');
    this.load();
  }

  onDeleted(): void {
    this.showDelete.set(false);
    this.toast.show('Obligation deleted.');
    this.load();
  }

  ordinal(day: number): string {
    const s = ['th', 'st', 'nd', 'rd'];
    const v = day % 100;
    return day + (s[(v - 20) % 10] ?? s[v] ?? s[0]);
  }

  periodLabel(period: string): string {
    return period === 'RECURRING' ? 'Recurring monthly' : 'Fixed term';
  }
}
