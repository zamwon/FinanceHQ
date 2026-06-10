export type TransactionType = 'EXPENSE' | 'INCOME';
export type ExpenseCategory = 'HOUSING' | 'FOOD' | 'TRANSPORT' | 'UTILITIES' | 'HEALTH' | 'ENTERTAINMENT' | 'OTHER';
export type IncomeCategory = 'SALARY' | 'FREELANCE' | 'INVESTMENT' | 'RENTAL' | 'OTHER';
export type TransactionCategory = ExpenseCategory | IncomeCategory;

export interface Transaction {
  id: string;
  type: TransactionType;
  category: TransactionCategory;
  amount: number;
  description: string | null;
  period: 'RECURRING' | 'FIXED_TERM' | null;
  date: string | null;
  paymentDay: number | null;
  endDate: string | null;
  remainingPayments: number | null;
  nextExpectedDate: string | null;
  obligationId: string | null;
  createdAt: string;
}

export const EXPENSE_CATEGORIES: ExpenseCategory[] = [
  'HOUSING', 'FOOD', 'TRANSPORT', 'UTILITIES', 'HEALTH', 'ENTERTAINMENT', 'OTHER',
];
export const INCOME_CATEGORIES: IncomeCategory[] = [
  'SALARY', 'FREELANCE', 'INVESTMENT', 'RENTAL', 'OTHER',
];
