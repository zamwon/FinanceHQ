import { Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

function passwordStrength(control: AbstractControl): ValidationErrors | null {
  const v: string = control.value ?? '';
  const errors: ValidationErrors = {};
  if (v.length < 8) errors['pwLength'] = true;
  if (!/[A-Z]/.test(v)) errors['pwUppercase'] = true;
  if (!/\d/.test(v)) errors['pwDigit'] = true;
  if (!/[^A-Za-z0-9]/.test(v)) errors['pwSpecial'] = true;
  return Object.keys(errors).length ? errors : null;
}

function passwordMatch(group: AbstractControl): ValidationErrors | null {
  const pw = group.get('password')?.value;
  const confirm = group.get('confirmPassword')?.value;
  return pw && confirm && pw !== confirm ? { passwordMismatch: true } : null;
}

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register.component.html',
  styleUrl: './register.component.scss',
})
export class RegisterComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);

  form = this.fb.group(
    {
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, passwordStrength]],
      confirmPassword: ['', Validators.required],
    },
    { validators: passwordMatch },
  );

  loading = signal(false);
  error = signal('');
  showPassword = signal(false);
  showConfirm = signal(false);
  passwordFocused = signal(false);

  get pw() { return this.form.controls.password; }
  get pwHints() {
    return [
      { key: 'pwLength', label: '≥ 8 characters' },
      { key: 'pwUppercase', label: '1 uppercase letter' },
      { key: 'pwDigit', label: '1 digit' },
      { key: 'pwSpecial', label: '1 special character' },
    ];
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set('');
    const { email, password } = this.form.getRawValue();
    this.auth.register({ email: email!, password: password! }).subscribe({
      next: () => this.router.navigate(['/login']),
      error: (err) => {
        this.loading.set(false);
        this.error.set(
          err.status === 409
            ? 'An account with this email already exists.'
            : 'Something went wrong. Please try again.',
        );
      },
    });
  }
}
