import { ChangeDetectorRef, Component, inject, NgZone, OnDestroy, OnInit } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs/operators';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltip } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarRef, TextOnlySnackBar } from '@angular/material/snack-bar';
import { Subscription } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';

const passwordValidators: ValidatorFn[] = [
  (c) => (c.value ?? '').length >= 8 ? null : { pwLength: true },
  (c) => /[A-Z]/.test(c.value ?? '') ? null : { pwUppercase: true },
  (c) => /\d/.test(c.value ?? '') ? null : { pwDigit: true },
  (c) => /[@#$%^&+=!?]/.test(c.value ?? '') ? null : { pwSpecial: true },
];

const passwordMatchValidator: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const password = group.get('password')?.value ?? '';
  const confirm = group.get('confirmPassword')?.value ?? '';
  return password === confirm ? null : { passwordMismatch: true };
};

@Component({
  selector: 'app-register',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatTooltip,
  ],
  templateUrl: './register.component.html',
  styleUrl: './register.component.scss',
})
export class RegisterComponent implements OnInit, OnDestroy {
  private fb = inject(FormBuilder);
  private authService = inject(AuthService);
  private router = inject(Router);
  private ngZone = inject(NgZone);
  private snackBar = inject(MatSnackBar);
  private cdr = inject(ChangeDetectorRef);

  protected registerForm = this.fb.group(
    {
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, ...passwordValidators]],
      confirmPassword: ['', Validators.required],
    },
    { validators: passwordMatchValidator }
  );

  protected loading = false;
  protected errorMessage: string | null = null;
  protected showPassword = false;
  protected showConfirm = false;

  private snackBarRef: MatSnackBarRef<TextOnlySnackBar> | null = null;
  private formChangeSub: Subscription | null = null;

  ngOnInit(): void {
    this.formChangeSub = this.registerForm.valueChanges.subscribe(() => {
      if (this.errorMessage) {
        this.errorMessage = null;
        this.snackBarRef?.dismiss();
        this.snackBarRef = null;
      }
    });
  }

  ngOnDestroy(): void {
    this.formChangeSub?.unsubscribe();
  }

  protected get passwordTooltip(): string {
    const ctrl = this.registerForm.controls.password;
    if (!ctrl.errors) return '';
    const msgs: string[] = [];
    if (ctrl.hasError('pwLength')) msgs.push('• At least 8 characters');
    if (ctrl.hasError('pwUppercase')) msgs.push('• Requires an uppercase letter');
    if (ctrl.hasError('pwDigit')) msgs.push('• Requires a digit');
    if (ctrl.hasError('pwSpecial')) msgs.push('• Requires a special character (@#$%^&+=!?)');
    return msgs.join('\n');
  }

  protected onPasswordBlur(tooltip: MatTooltip): void {
    if (this.registerForm.controls.password.invalid && this.registerForm.controls.password.touched) {
      tooltip.show();
      setTimeout(() => tooltip.hide(), 4000);
    }
  }

  protected submit(): void {
    if (this.registerForm.invalid || this.loading) return;
    this.loading = true;
    this.errorMessage = null;

    const { email, password } = this.registerForm.value;
    const credentials = { email: email!, password: password! };

    this.authService
      .register(credentials)
      .pipe(switchMap(() => this.authService.login(credentials)))
      .subscribe({
        next: () => this.router.navigateByUrl('/dashboard'),
        error: (err) => {
          this.ngZone.run(() => {
            this.loading = false;
            const msg =
              err.status === 409
                ? 'This email is already registered. Please use a different email or sign in.'
                : 'Registration failed. Please try again.';
            this.errorMessage = msg;
            this.snackBarRef = this.snackBar.open(msg, 'Dismiss', {
              duration: 6000,
              panelClass: 'register-error-snack',
              verticalPosition: 'top',
              horizontalPosition: 'center',
            });
            this.cdr.detectChanges();
          });
        },
      });
  }
}
