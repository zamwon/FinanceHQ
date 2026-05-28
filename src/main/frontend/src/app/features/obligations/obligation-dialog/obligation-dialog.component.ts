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
    name: ['', [Validators.required, Validators.maxLength(100)]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01), Validators.max(999999.99)]],
    category: ['TOP' as 'TOP' | 'HIGH' | 'LOW', Validators.required],
    period: ['RECURRING' as 'RECURRING' | 'FIXED_TERM', Validators.required],
    paymentDay: [null as number | null, [Validators.required, Validators.min(1), Validators.max(31)]],
  });

  get isEdit(): boolean { return this.obligation !== null; }

  ngOnInit(): void {
    if (this.obligation) {
      this.form.patchValue({
        name: this.obligation.name,
        amount: this.obligation.amount,
        category: this.obligation.category,
        period: this.obligation.period,
        paymentDay: this.obligation.paymentDay,
      });
      // lock read-only fields in edit mode
      this.form.controls.name.disable();
      this.form.controls.category.disable();
      this.form.controls.period.disable();
    }
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set('');
    const v = this.form.getRawValue();

    const call = this.isEdit
      ? this.svc.update(this.obligation!.id, { amount: v.amount!, paymentDay: v.paymentDay! })
      : this.svc.create({ name: v.name!, amount: v.amount!, category: v.category!, period: v.period!, paymentDay: v.paymentDay! });

    call.subscribe({
      next: () => this.saved.emit(),
      error: () => { this.loading.set(false); this.error.set('Failed to save. Please try again.'); },
    });
  }
}
