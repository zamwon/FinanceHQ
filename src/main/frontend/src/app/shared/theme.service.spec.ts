import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  let service: ThemeService;

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark');
    TestBed.configureTestingModule({});
    service = TestBed.inject(ThemeService);
  });

  it('should apply dark class when localStorage theme is dark', () => {
    localStorage.setItem('theme', 'dark');
    service.init();
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('should not apply dark class when localStorage theme is light', () => {
    localStorage.setItem('theme', 'light');
    service.init();
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('should not apply dark class when no theme in localStorage', () => {
    service.init();
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('should toggle dark class on and persist to localStorage', () => {
    service.init();
    service.toggle();
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  it('should toggle dark class off and persist to localStorage', () => {
    localStorage.setItem('theme', 'dark');
    service.init();
    service.toggle();
    expect(document.documentElement.classList.contains('dark')).toBe(false);
    expect(localStorage.getItem('theme')).toBe('light');
  });

  it('should expose isDark signal reflecting current state', () => {
    localStorage.setItem('theme', 'dark');
    service.init();
    expect(service.isDark()).toBe(true);
    service.toggle();
    expect(service.isDark()).toBe(false);
  });
});
