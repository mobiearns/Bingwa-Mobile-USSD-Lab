export type OfferCategory = 'Data' | 'Calls' | 'SMS';
export type ExecutionMode = 'SIMPLE' | 'ADVANCED';
export type SimSlot = 0 | 1 | 2; // 0 = General, 1 = Slot 1, 2 = Slot 2
export type TransactionStatus = 'Pending' | 'Processing' | 'Success' | 'Failed' | 'Cancelled' | 'Retrying';

export interface OfferItem {
  id: number;
  catalogKey: string;
  name: string;
  price: number;
  ussdCode: string;
  catalogDefaultUssdCode?: string;
  enabled: boolean;
  category: OfferCategory;
  executionMode: ExecutionMode;
  simSelection: SimSlot;
  targetDevice?: 'PRIMARY' | 'RELAY';
}

export interface Transaction {
  id: number;
  description: string;
  amount: string;
  amountValue: number;
  date: string;
  status: TransactionStatus;
  ussdCode: string;
  phoneNumber: string;
  clientName: string;
  response: string;
  ussdTranscript: string;
  executionDurationMs: number;
  source: 'AUTOMATED' | 'CONSOLE' | 'SMS_COMMAND' | 'AIRTIME' | 'SYSTEM';
  timestamp: number;
}

export interface SavedContact {
  id: string;
  name: string;
  phone: string;
  notes?: string;
  lastUsed?: number;
}

export interface BlacklistedContact {
  id: string;
  phone: string;
  reason: string;
  addedAt: number;
}

export interface UnlimitedPlan {
  id: string;
  label: string;
  ksh: number;
  durationMs: number;
}

export interface ActiveUnlimited {
  planId: string;
  label: string;
  expiresAt: number;
}

export interface DiagnosticCheck {
  id: string;
  title: string;
  description: string;
  status: 'passed' | 'warning' | 'failed' | 'checking';
  detail: string;
  remedy?: string;
}

export interface AppSettings {
  automationLive: boolean;
  autoProcessMpesaSms: boolean;
  defaultSimSlot: SimSlot;
  dailySpendLimitKsh: number;
  currentDaySpendKsh: number;
  relayEnabled: boolean;
  relayPort: number;
  relayTargetDevice: string;
  subFrameOptimization: boolean;
  preFocusEnabled: boolean;
  boundedRefreshEnabled: boolean;
}
