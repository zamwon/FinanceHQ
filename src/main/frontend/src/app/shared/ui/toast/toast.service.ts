import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ToastService {
  toast = signal<string | null>(null);
  private timerId: ReturnType<typeof setTimeout> | null = null;

  show(text: string, duration = 3000): void {
    if (this.timerId !== null) {
      clearTimeout(this.timerId);
    }
    this.toast.set(text);
    this.timerId = setTimeout(() => { this.toast.set(null); this.timerId = null; }, duration);
  }
}
