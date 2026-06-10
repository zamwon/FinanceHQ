export interface CategoryBreakdownItem {
  category: string;
  total: number;
  count: number;
}

export interface MonthlySummaryResponse {
  month: string;
  totalIncome: number;
  totalExpenses: number;
  netBalance: number;
  expensesByCategory: CategoryBreakdownItem[];
  incomeByCategory: CategoryBreakdownItem[];
}

export interface MonthlyTrendItem {
  month: string;
  totalIncome: number;
  totalExpenses: number;
  netBalance: number;
}
