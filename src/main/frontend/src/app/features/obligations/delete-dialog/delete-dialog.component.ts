import { Component, Input, Output, EventEmitter, inject, signal } from '@angular/core';
import { Obligation } from '../obligation.model';
import { ObligationsService } from '../obligations.service';

@Component({
  selector: 'app-delete-dialog',
  templateUrl: './delete-dialog.component.html',
})
export class DeleteDialogComponent {
  @Input({ required: true }) obligation!: Obligation;
  @Output() deleted = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  private svc = inject(ObligationsService);
  loading = signal(false);

  confirm(): void {
    this.loading.set(true);
    this.svc.delete(this.obligation.id).subscribe({
      next: () => this.deleted.emit(),
      error: () => this.loading.set(false),
    });
  }
}
