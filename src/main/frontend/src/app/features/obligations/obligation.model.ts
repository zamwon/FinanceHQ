export interface Obligation {
  id: string;
  name: string;
  amount: number;
  category: 'TOP' | 'HIGH' | 'LOW';
  period: 'RECURRING' | 'FIXED_TERM';
  paymentDay: number;
  createdAt: string;
}

export type CreateObligationDto = Omit<Obligation, 'id' | 'createdAt'>;
export type UpdateObligationDto = Pick<Obligation, 'amount' | 'paymentDay'>;
