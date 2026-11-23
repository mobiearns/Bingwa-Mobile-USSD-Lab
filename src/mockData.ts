import { OfferItem, Transaction, SavedContact, BlacklistedContact, UnlimitedPlan, AppSettings } from './types';

export const INITIAL_OFFERS: OfferItem[] = [
  {
    id: 0,
    catalogKey: 'sms_daily_20',
    name: '20 SMS Daily',
    price: 5,
    ussdCode: '*188*10*1*1*pn*1*2#',
    catalogDefaultUssdCode: '*188*10*1*1*pn*1*2#',
    enabled: true,
    category: 'SMS',
    executionMode: 'ADVANCED',
    simSelection: 0,
    targetDevice: 'PRIMARY'
  },
  {
    id: 1,
    catalogKey: 'data_hourly_1gb',
    name: '1GB 1 Hour',
    price: 19,
    ussdCode: '*180*5*2*pn*5*1#',
    catalogDefaultUssdCode: '*180*5*2*pn*5*1#',
    enabled: true,
    category: 'Data',
    executionMode: 'SIMPLE',
    simSelection: 1,
    targetDevice: 'PRIMARY'
  },
  {
    id: 2,
    catalogKey: 'data_daily_250mb',
    name: '250MB 24 Hours',
    price: 20,
    ussdCode: '*180*5*2*pn*6*1#',
    catalogDefaultUssdCode: '*180*5*2*pn*6*1#',
    enabled: true,
    category: 'Data',
    executionMode: 'SIMPLE',
    simSelection: 1,
    targetDevice: 'PRIMARY'
  },
  {
    id: 3,
    catalogKey: 'flex_350_2h',
    name: '350 Flex 2 Hours',
    price: 21,
    ussdCode: '*444*5*1*3*pn*2*1#',
    catalogDefaultUssdCode: '*444*5*1*3*pn*2*1#',
    enabled: true,
    category: 'Data',
    executionMode: 'SIMPLE',
    simSelection: 0,
    targetDevice: 'PRIMARY'
  },
  {
    id: 4,
    catalogKey: 'sms_weekly_1000',
    name: '1000 Weekly SMS',
    price: 30,
    ussdCode: '*188*10*2*2*pn*1*2#',
    catalogDefaultUssdCode: '*188*10*2*2*pn*1*2#',
    enabled: true,
    category: 'SMS',
    executionMode: 'ADVANCED',
    simSelection: 0,
    targetDevice: 'PRIMARY'
  },
  {
    id: 5,
    catalogKey: 'minutes_midnight_50',
    name: '50 Minutes Till Midnight',
    price: 51,
    ussdCode: '*444*7*8*3*pn*2*1#',
    catalogDefaultUssdCode: '*444*7*8*3*pn*2*1#',
    enabled: true,
    category: 'Calls',
    executionMode: 'ADVANCED',
    simSelection: 1,
    targetDevice: 'PRIMARY'
  },
  {
    id: 6,
    catalogKey: 'data_midnight_1250mb',
    name: '1.25 GB Till Midnight',
    price: 55,
    ussdCode: '*180*5*2*pn*8*1#',
    catalogDefaultUssdCode: '*180*5*2*pn*8*1#',
    enabled: true,
    category: 'Data',
    executionMode: 'SIMPLE',
    simSelection: 1,
    targetDevice: 'PRIMARY'
  },
  {
    id: 7,
    catalogKey: 'data_monthly_5gb',
    name: '5GB Monthly Data',
    price: 499,
    ussdCode: '*544*2*1*pn*1#',
    catalogDefaultUssdCode: '*544*2*1*pn*1#',
    enabled: true,
    category: 'Data',
    executionMode: 'SIMPLE',
    simSelection: 1,
    targetDevice: 'PRIMARY'
  }
];

export const INITIAL_TRANSACTIONS: Transaction[] = [
  {
    id: 9021,
    description: '1GB 1 Hour Bundle',
    amount: 'Ksh 19.00',
    amountValue: 19.0,
    date: 'Just now',
    status: 'Success',
    ussdCode: '*180*5*2*0712345678*5*1#',
    phoneNumber: '0712345678',
    clientName: 'John Kamau',
    response: 'Confirmed. Ksh 19 spent. 1GB bundle valid for 1 hour dispatched to 0712345678. Bal: Ksh 1,420.50.',
    ussdTranscript: 'Session 1092-A:\n[0.00s] Dialing *180*5*2*0712345678*5*1# on SIM 1 (Safaricom)\n[0.02s] Node Pre-Focus applied (13.2ms rejected IPC avoided)\n[0.04s] Sent option 1 (Confirm Purchase)\n[0.08s] Response received: "Confirmed. 1GB valid for 1 hour assigned."\n[0.08s] Session closed cleanly.',
    executionDurationMs: 46,
    source: 'AUTOMATED',
    timestamp: Date.now() - 1000 * 60 * 3
  },
  {
    id: 9020,
    description: '250MB 24 Hours Bundle',
    amount: 'Ksh 20.00',
    amountValue: 20.0,
    date: '14 mins ago',
    status: 'Success',
    ussdCode: '*180*5*2*0722998877*6*1#',
    phoneNumber: '0722998877',
    clientName: 'Faith Wanjiku',
    response: 'Success. Ksh 20 paid for 250MB (24h) to 0722998877. New Airtime Bal: Ksh 1,439.50.',
    ussdTranscript: 'Session 1091-C:\n[0.00s] Dialing *180*5*2*0722998877*6*1# on SIM 1\n[0.03s] Pre-focus validated -> node refresh bounded (1 pass)\n[0.05s] Option 1 confirmed\n[0.07s] Carrier confirmation dialog closed.',
    executionDurationMs: 44,
    source: 'AUTOMATED',
    timestamp: Date.now() - 1000 * 60 * 14
  },
  {
    id: 9019,
    description: '50 Minutes Till Midnight',
    amount: 'Ksh 51.00',
    amountValue: 51.0,
    date: '42 mins ago',
    status: 'Success',
    ussdCode: '*444*7*8*3*0700112233*2*1#',
    phoneNumber: '0700112233',
    clientName: 'Brian Otieno',
    response: 'Tunukiwa: 50 Voice Mins activated for 0700112233 until 23:59 tonight.',
    ussdTranscript: 'Session 1090-B:\n[0.00s] Dialing *444*7*8*3*0700112233*2*1# on SIM 1\n[0.02s] Bounded refresh pass executed\n[0.06s] IME send method used (8.2ms acceptance)\n[0.09s] Voice minutes confirmed.',
    executionDurationMs: 82,
    source: 'CONSOLE',
    timestamp: Date.now() - 1000 * 60 * 42
  },
  {
    id: 9018,
    description: 'Manual USSD Check',
    amount: 'Ksh 0.00',
    amountValue: 0.0,
    date: '1 hour ago',
    status: 'Success',
    ussdCode: '*144#',
    phoneNumber: 'Self',
    clientName: 'Console Terminal',
    response: 'Airtime balance: Ksh 1,490.50. Bonga Points: 3,420. Valid until: 12-Dec-2026.',
    ussdTranscript: 'Session 1089-A:\n[0.00s] Dialing *144# on SIM 1\n[0.02s] Carrier response: Balance Ksh 1490.50\n[0.03s] Session closed.',
    executionDurationMs: 31,
    source: 'CONSOLE',
    timestamp: Date.now() - 1000 * 60 * 75
  },
  {
    id: 9017,
    description: '20 SMS Daily',
    amount: 'Ksh 5.00',
    amountValue: 5.0,
    date: '3 hours ago',
    status: 'Success',
    ussdCode: '*188*10*1*1*0799443322*1*2#',
    phoneNumber: '0799443322',
    clientName: 'Grace Achieng',
    response: '20 SMS dispatched to 0799443322. Cost Ksh 5. Valid for 24h.',
    ussdTranscript: 'Session 1088-F:\n[0.00s] Dialing *188*10*1*1*0799443322*1*2#\n[0.03s] Accepted\n[0.05s] Completed.',
    executionDurationMs: 51,
    source: 'SMS_COMMAND',
    timestamp: Date.now() - 1000 * 60 * 180
  }
];

export const INITIAL_CONTACTS: SavedContact[] = [
  { id: 'c1', name: 'John Kamau', phone: '0712345678', notes: 'Daily 1GB reseller', lastUsed: Date.now() - 180000 },
  { id: 'c2', name: 'Faith Wanjiku', phone: '0722998877', notes: 'Frequent client', lastUsed: Date.now() - 840000 },
  { id: 'c3', name: 'Brian Otieno', phone: '0700112233', notes: 'Voice bundle subscriber', lastUsed: Date.now() - 2520000 },
  { id: 'c4', name: 'Grace Achieng', phone: '0799443322', notes: 'SMS bulk agent', lastUsed: Date.now() - 10800000 },
  { id: 'c5', name: 'Kiprono Davis', phone: '0745123987', notes: 'Wholesale partner' }
];

export const INITIAL_BLACKLIST: BlacklistedContact[] = [
  { id: 'b1', phone: '0799999999', reason: 'Fraudulent transaction attempts / disputed reversals', addedAt: Date.now() - 86400000 * 5 },
  { id: 'b2', phone: '0711000000', reason: 'Unsubscribed from automated dispatch alerts', addedAt: Date.now() - 86400000 * 12 }
];

export const UNLIMITED_PLANS: UnlimitedPlan[] = [
  { id: 'unlim_1h', label: '1 Hour Unlimited Flash Pass', ksh: 20, durationMs: 1000 * 60 * 60 },
  { id: 'unlim_24h', label: '24 Hours Unlimited Pro', ksh: 100, durationMs: 1000 * 60 * 60 * 24 },
  { id: 'unlim_7d', label: '7 Days Unlimited Enterprise', ksh: 450, durationMs: 1000 * 60 * 60 * 24 * 7 },
  { id: 'unlim_30d', label: '30 Days Unlimited Reseller', ksh: 1200, durationMs: 1000 * 60 * 60 * 24 * 30 }
];

export const INITIAL_SETTINGS: AppSettings = {
  automationLive: true,
  autoProcessMpesaSms: true,
  defaultSimSlot: 1,
  dailySpendLimitKsh: 5000,
  currentDaySpendKsh: 95,
  relayEnabled: true,
  relayPort: 8888,
  relayTargetDevice: 'Infinix X669 (Relay Hotspot #1)',
  subFrameOptimization: true,
  preFocusEnabled: true,
  boundedRefreshEnabled: true
};
