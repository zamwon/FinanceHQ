import { Component, Input, Output, EventEmitter, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Obligation } from '../obligation.model';
import { ObligationsService } from '../obligations.service';

@Component({
  selector: 'app-obligation-dialog',
  imports: [ReactiveFormsModule],
  templateUrl: './obligation-dialog.component.html',
})
export class ObligationDialogComponent implements OnInit {
  @Input() obligation: Obligation | null = null; // null = add mode
  @Output() saved = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private svc = inject(ObligationsService);

  loading = signal(false);
  error = signal('');

  form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01), Validators.max(999999.99)]],
    category: ['ESSENTIAL' as 'ESSENTIAL' | 'IMPORTANT' | 'OPTIONAL', Validators.required],
    period: ['RECURRING' as 'RECURRING' | 'FIXED_TERM', Validators.required],
    paymentDay: [null as number | null, [Validators.required, Validators.min(1), Validators.max(31)]],
    endDate: [null as string | null, []],
    remainingPayments: [null as number | null, [Validators.min(1)]],
  });

  get isEdit(): boolean { return this.obligation !== null; }
  get isFixedTerm(): boolean { return this.form.controls.period.value === 'FIXED_TERM'; }

  ngOnInit(): void {
    if (this.obligation) {
      this.form.patchValue({
        name: this.obligation.name,
        amount: this.obligation.amount,
        category: this.obligation.category,
        period: this.obligation.period,
        paymentDay: this.obligation.paymentDay,
        endDate: this.obligation.endDate,
        remainingPayments: this.obligation.remainingPayments,
      });
      // lock read-only fields in edit mode — patchValue must run before disable()
      // so that isFixedTerm getter reads the correct value from the disabled control
      this.form.controls.name.disable();
      this.form.controls.category.disable();
      this.form.controls.period.disable();
      this.form.controls.endDate.disable();
      this.form.controls.remainingPayments.disable();
    }

    this.updateFixedTermValidators(this.form.controls.period.value);
    this.form.controls.period.valueChanges.subscribe(v => this.updateFixedTermValidators(v));
  }

  private updateFixedTermValidators(period: string | null): void {
    const endDate = this.form.controls.endDate;
    const remaining = this.form.controls.remainingPayments;
    if (period === 'FIXED_TERM') {
      endDate.setValidators([Validators.required]);
      remaining.setValidators([Validators.required, Validators.min(1)]);
    } else {
      endDate.clearValidators();
      remaining.setValidators([Validators.min(1)]);
    }
    endDate.updateValueAndValidity();
    remaining.updateValueAndValidity();
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set('');
    const v = this.form.getRawValue();

    const call = this.isEdit
      ? this.svc.update(this.obligation!.id, { amount: v.amount!, paymentDay: v.paymentDay! })
      : this.svc.create({
          name: v.name!,
          amount: v.amount!,
          category: v.category!,
          period: v.period!,
          paymentDay: v.paymentDay!,
          endDate: this.isFixedTerm ? v.endDate : null,
          remainingPayments: this.isFixedTerm ? v.remainingPayments : null,
        });

    call.subscribe({
      next: () => { this.loading.set(false); this.saved.emit(); },
      error: () => { this.loading.set(false); this.error.set('Failed to save. Please try again.'); },
    });
  }
}
