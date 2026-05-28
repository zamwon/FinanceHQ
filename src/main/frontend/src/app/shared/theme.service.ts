import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  isDark = signal(false);

  init(): void {
    const stored = localStorage.getItem('theme');
    const dark = stored === 'dark';
    this.isDark.set(dark);
    this.applyClass(dark);
  }

  toggle(): void {
    const next = !this.isDark();
    this.isDark.set(next);
    this.applyClass(next);
    localStorage.setItem('theme', next ? 'dark' : 'light');
  }

  private applyClass(dark: boolean): void {
    document.documentElement.classList.toggle('dark', dark);
  }
}
