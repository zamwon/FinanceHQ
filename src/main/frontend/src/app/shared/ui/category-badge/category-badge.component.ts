import { Component, Input } from '@angular/core';

type Category = 'TOP' | 'HIGH' | 'LOW';

@Component({
  selector: 'app-category-badge',
  standalone: true,
  templateUrl: './category-badge.component.html',
})
export class CategoryBadgeComponent {
  @Input({ required: true }) category!: Category;

  get classes(): string {
    const map: Record<Category, string> = {
      TOP: 'bg-red-50 text-red-600 border-red-200 dark:bg-red-950 dark:text-red-400 dark:border-red-900',
      HIGH: 'bg-amber-50 text-amber-600 border-amber-200 dark:bg-amber-950 dark:text-amber-400 dark:border-amber-900',
      LOW: 'bg-green-50 text-green-600 border-green-200 dark:bg-green-950 dark:text-green-400 dark:border-green-900',
    };
    return map[this.category];
  }
}
