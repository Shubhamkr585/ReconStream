export type Role = 'ADMIN' | 'AUDITOR' | 'ANALYST';

export interface User {
  email: string;
  role: Role;
}

export interface ReconSummary {
  totalOrders: number;
  totalSettlementRows: number;
  settledOrders: number;
  unmatchedOrders: number;
  partialMatches: number;
  unmatchedSettlements: number;
  amountDiscrepancies: number;
  discrepancyCountByType: Record<string, number>;
  discrepancyCountByStatus: Record<string, number>;
  latestReconTimestamp: string | null;
}

export interface Discrepancy {
  id: string;
  externalOrderId: string;
  discrepancyType: string;
  status: string;
  details: string;
  embedding?: number[];
  createdAt: string;
  resolvedAt?: string | null;
  resolutionComment?: string | null;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface LedgerEntry {
  id: string;
  referenceId: string;
  eventType: string;
  accountName: string;
  direction: 'DEBIT' | 'CREDIT';
  amountPaise: number;
  createdAt: string;
}

export interface AccountSummary {
  accountName: string;
  totalDebitPaise: number;
  totalCreditPaise: number;
  netBalancePaise: number;
}

export interface SimilarDiscrepancy {
  id: string;
  externalOrderId: string;
  discrepancyType: string;
  details: string;
  similarityScore: number;
}

export interface AiTriageResponse {
  externalOrderId: string;
  analysis: string;
  recommendation: string;
  rootCauseCategory: string;
  discrepancyId?: string;
  reconciliationState?: string;
}

export interface ChaosRunResponse {
  ordersGenerated: number;
  webhooksSent: number;
  duplicatesSent: number;
  outOfOrderSent: number;
  droppedCount: number;
  simulatedAt: string;
}
