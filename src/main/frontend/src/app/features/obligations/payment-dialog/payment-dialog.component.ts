import { Component, Input, Output, EventEmitter, OnInit, inject, signal, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Obligation } from '../obligation.model';
import { ObligationsService } from '../obligations.service';
import { EXPENSE_CATEGORIES } from '../../transactions/transaction.model';

@Component({
  standalone: true,
  selector: 'app-payment-dialog',
  imports: [ReactiveFormsModule],
  templateUrl: './payment-dialog.component.html',
})
export class PaymentDialogComponent implements OnInit {
  @Input({ required: true }) obligation!: Obligation;
  @Output() paid = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private svc = inject(ObligationsService);
  private destroyRef = inject(DestroyRef);

  loading = signal(false);
  error = signal('');

  readonly expenseCategories = EXPENSE_CATEGORIES;

  form = this.fb.group({
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    category: ['', Validators.required],
    description: [null as string | null, [Validators.maxLength(255)]],
    date: ['', Validators.required],
  });

  ngOnInit(): void {
    const today = new Date().toISOString().split('T')[0];
    this.form.patchValue({
      amount: this.obligation.amount,
      description: this.obligation.name,
      date: today,
    });
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set('');
    const v = this.form.getRawValue();

    this.svc.markPaid(this.obligation.id, {
      amount: v.amount,
      category: v.category,
      description: v.description ?? null,
      date: v.date,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.loading.set(false); this.paid.emit(); },
      error: () => { this.loading.set(false); this.error.set('Failed to record payment. Please try again.'); },
    });
  }
}
