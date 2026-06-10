import { Component, Input, Output, EventEmitter, OnInit, inject, signal, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Transaction, TransactionType, TransactionCategory, EXPENSE_CATEGORIES, INCOME_CATEGORIES } from '../transaction.model';
import { TransactionsService } from '../transactions.service';

@Component({
  standalone: true,
  selector: 'app-transaction-dialog',
  imports: [ReactiveFormsModule],
  templateUrl: './transaction-dialog.component.html',
})
export class TransactionDialogComponent implements OnInit {
  @Input() transaction: Transaction | null = null;
  @Output() saved = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private svc = inject(TransactionsService);
  private destroyRef = inject(DestroyRef);

  loading = signal(false);
  error = signal('');
  categoryOptions = signal<string[]>(EXPENSE_CATEGORIES);

  readonly expenseCategories = EXPENSE_CATEGORIES;
  readonly incomeCategories = INCOME_CATEGORIES;

  form = this.fb.group({
    type: ['EXPENSE' as TransactionType, Validators.required],
    category: ['' as TransactionCategory | '', Validators.required],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    description: [null as string | null, [Validators.maxLength(255)]],
    period: [null as 'RECURRING' | 'FIXED_TERM' | null],
    date: [null as string | null],
    paymentDay: [null as number | null, [Validators.min(1), Validators.max(31)]],
    endDate: [null as string | null],
    remainingPayments: [null as number | null, [Validators.min(1)]],
  });

  get isEdit(): boolean { return this.transaction !== null; }
  get isRecurring(): boolean { return this.form.controls.period.value !== null; }
  get isFixedTerm(): boolean { return this.form.controls.period.value === 'FIXED_TERM'; }

  ngOnInit(): void {
    if (this.transaction) {
      const t = this.transaction;
      this.form.patchValue({
        type: t.type,
        category: t.category,
        amount: t.amount,
        description: t.description,
        period: t.period ?? null,
        date: t.date,
        paymentDay: t.paymentDay,
        endDate: t.endDate,
        remainingPayments: t.remainingPayments,
      });
      this.categoryOptions.set(t.type === 'EXPENSE' ? EXPENSE_CATEGORIES : INCOME_CATEGORIES);
    }

    this.updateDateValidators(this.form.controls.period.value);

    this.form.controls.type.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(type => {
        this.categoryOptions.set(type === 'EXPENSE' ? EXPENSE_CATEGORIES : INCOME_CATEGORIES);
        this.form.controls.category.setValue('' as TransactionCategory);
      });

    this.form.controls.period.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(period => this.updateDateValidators(period));
  }

  private updateDateValidators(period: 'RECURRING' | 'FIXED_TERM' | null): void {
    const date = this.form.controls.date;
    const paymentDay = this.form.controls.paymentDay;
    const endDate = this.form.controls.endDate;
    const remaining = this.form.controls.remainingPayments;

    if (period === null) {
      date.setValidators([Validators.required]);
      paymentDay.clearValidators();
      endDate.clearValidators();
      remaining.clearValidators();
    } else if (period === 'RECURRING') {
      date.clearValidators();
      paymentDay.setValidators([Validators.required, Validators.min(1), Validators.max(31)]);
      endDate.clearValidators();
      remaining.clearValidators();
    } else {
      date.clearValidators();
      paymentDay.setValidators([Validators.required, Validators.min(1), Validators.max(31)]);
      endDate.setValidators([Validators.required]);
      remaining.setValidators([Validators.required, Validators.min(1)]);
    }

    date.updateValueAndValidity();
    paymentDay.updateValueAndValidity();
    endDate.updateValueAndValidity();
    remaining.updateValueAndValidity();
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set('');
    const v = this.form.getRawValue();

    const payload: Record<string, unknown> = {
      type: v.type,
      category: v.category,
      amount: v.amount,
      description: v.description ?? null,
      period: v.period ?? null,
      date: v.period === null ? v.date : null,
      paymentDay: v.period !== null ? v.paymentDay : null,
      endDate: v.period === 'FIXED_TERM' ? v.endDate : null,
      remainingPayments: v.period === 'FIXED_TERM' ? v.remainingPayments : null,
    };

    const call = this.isEdit
      ? this.svc.update(this.transaction!.id, payload)
      : this.svc.create(payload);

    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.loading.set(false); this.saved.emit(); },
      error: () => { this.loading.set(false); this.error.set('Failed to save. Please try again.'); },
    });
  }
}
