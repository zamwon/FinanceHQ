export interface Obligation {
  id: string;
  name: string;
  amount: number;
  category: 'ESSENTIAL' | 'IMPORTANT' | 'OPTIONAL';
  period: 'RECURRING' | 'FIXED_TERM';
  paymentDay: number;
  endDate: string | null;
  remainingPayments: number | null;
  nextDueDate: string | null;
  createdAt: string;
}

export type CreateObligationDto = Omit<Obligation, 'id' | 'createdAt' | 'nextDueDate'>;
export type UpdateObligationDto = Partial<Pick<Obligation, 'amount' | 'paymentDay' | 'category'>>;
