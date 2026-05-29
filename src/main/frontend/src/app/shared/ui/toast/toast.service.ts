import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ToastService {
  toast = signal<string | null>(null);

  show(text: string, duration = 3000): void {
    this.toast.set(text);
    setTimeout(() => this.toast.set(null), duration);
  }
}
