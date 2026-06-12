import { Injectable, signal } from '@angular/core';

export interface ToastMessage { text: string; isError: boolean; }

@Injectable({ providedIn: 'root' })
export class ToastService {
  toast = signal<ToastMessage | null>(null);
  private timerId: ReturnType<typeof setTimeout> | null = null;

  show(text: string, duration = 3000, isError = false): void {
    if (this.timerId !== null) {
      clearTimeout(this.timerId);
    }
    this.toast.set({ text, isError });
    this.timerId = setTimeout(() => { this.toast.set(null); this.timerId = null; }, duration);
  }
}
