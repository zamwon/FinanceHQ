import { Component, Input, Output, EventEmitter, inject, signal, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Obligation } from '../obligation.model';
import { ObligationsService } from '../obligations.service';

@Component({
  standalone: true,
  selector: 'app-delete-dialog',
  templateUrl: './delete-dialog.component.html',
})
export class DeleteDialogComponent {
  @Input({ required: true }) obligation!: Obligation;
  @Output() deleted = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  private svc = inject(ObligationsService);
  private destroyRef = inject(DestroyRef);
  loading = signal(false);
  error = signal('');

  confirm(): void {
    this.loading.set(true);
    this.error.set('');
    this.svc.delete(this.obligation.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.deleted.emit(),
        error: () => { this.loading.set(false); this.error.set('Failed to delete. Please try again.'); },
      });
  }
}
