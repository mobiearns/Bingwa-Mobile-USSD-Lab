import React, { useState } from 'react';
import { Terminal, Phone, Send, Trash2, Cpu, CheckCircle2, AlertTriangle, ArrowRight, CornerDownLeft } from 'lucide-react';
import { SimSlot, Transaction } from '../types';

interface TerminalViewProps {
  onDialSuccess: (tx: Transaction) => void;
  airtimeBalance: number;
}

interface UssdSessionState {
  isActive: boolean;
  currentDialogText: string;
  step: number;
  ussdCode: string;
  simSlot: SimSlot;
  history: { type: 'out' | 'in'; text: string; time: string; latencyMs: number }[];
}

export const TerminalView: React.FC<TerminalViewProps> = ({ onDialSuccess, airtimeBalance }) => {
  const [dialCode, setDialCode] = useState('*144#');
  const [selectedSim, setSelectedSim] = useState<SimSlot>(1);
  const [session, setSession] = useState<UssdSessionState>({
    isActive: false,
    currentDialogText: '',
    step: 0,
    ussdCode: '',
    simSlot: 1,
    history: []
  });
  const [replyInput, setReplyInput] = useState('');
  const [isDialing, setIsDialing] = useState(false);

  const presets = [
    { label: 'Check Airtime', code: '*144#' },
    { label: 'Buy Data (Tunukiwa)', code: '*544#' },
    { label: 'Okoa Jahazi', code: '*131#' },
    { label: 'M-PESA Access', code: '*334#' },
    { label: 'Special Offers', code: '*444#' },
    { label: 'Daily Data 1GB', code: '*180*5*2#' },
  ];

  const handleDial = () => {
    if (!dialCode.trim()) return;
    setIsDialing(true);

    const now = new Date().toLocaleTimeString();
    const code = dialCode.trim();

    setTimeout(() => {
      setIsDialing(false);

      let responseText = '';
      if (code.includes('144')) {
        responseText = `Balance Enquiry:\nAirtime: Ksh ${airtimeBalance.toFixed(2)}\nBonga Points: 3,420\nExpiry: 31-Dec-2026\n0: Back`;
      } else if (code.includes('544')) {
        responseText = `Tunukiwa Data Deals:\n1: 1GB 1hr @ Ksh 19\n2: 250MB 24hrs @ Ksh 20\n3: 1.25GB Till Midnight @ Ksh 55\n4: Monthly 5GB @ Ksh 499\n0: More Offers`;
      } else if (code.includes('334')) {
        responseText = `M-PESA Menu:\n1: Send Money\n2: Withdraw Cash\n3: Buy Airtime\n4: Lipa na M-PESA\n5: My Account`;
      } else if (code.includes('180')) {
        responseText = `Special Data Services:\n1: 1GB 1hr (Ksh 19)\n2: 250MB Daily (Ksh 20)\n3: Check Balances`;
      } else {
        responseText = `Carrier Menu (${code}):\n1: Proceed to execute\n2: Check Service Status\n3: View Account Summary\n0: Cancel`;
      }

      setSession({
        isActive: true,
        currentDialogText: responseText,
        step: 1,
        ussdCode: code,
        simSlot: selectedSim,
        history: [
          {
            type: 'out',
            text: `Dialing ${code} via SIM ${selectedSim}`,
            time: now,
            latencyMs: 24
          },
          {
            type: 'in',
            text: responseText,
            time: now,
            latencyMs: 42
          }
        ]
      });
      setReplyInput('');
    }, 450);
  };

  const handleSendReply = () => {
    if (!replyInput.trim()) return;
    const now = new Date().toLocaleTimeString();
    const input = replyInput.trim();

    const newHistory = [
      ...session.history,
      {
        type: 'out' as const,
        text: `Reply: "${input}" (IME Send Action)`,
        time: now,
        latencyMs: 8
      }
    ];

    let nextResponse = '';
    let isTerminating = false;

    if (session.step === 1) {
      if (input === '1') {
        nextResponse = `Selected option 1. Confirm transaction with MPESA PIN or Airtime balance.\n1: Confirm with Airtime\n2: Cancel`;
      } else if (input === '2') {
        nextResponse = `Selected option 2. Dispatched request to network core.\nService active within 60 seconds.`;
        isTerminating = true;
      } else {
        nextResponse = `Processed input "${input}". Request submitted successfully to gateway.`;
        isTerminating = true;
      }
    } else {
      nextResponse = `Confirmed! Order complete. Dispatched via Sub-Frame engine in 45ms.`;
      isTerminating = true;
    }

    setTimeout(() => {
      newHistory.push({
        type: 'in' as const,
        text: nextResponse,
        time: new Date().toLocaleTimeString(),
        latencyMs: 38
      });

      if (isTerminating) {
        setSession({
          ...session,
          currentDialogText: nextResponse,
          isActive: false,
          history: newHistory
        });

        // Record successful transaction in history
        onDialSuccess({
          id: Math.floor(1000 + Math.random() * 9000),
          description: `USSD Dial: ${session.ussdCode}`,
          amount: 'Ksh 0.00',
          amountValue: 0.0,
          date: 'Just now',
          status: 'Success',
          ussdCode: session.ussdCode,
          phoneNumber: 'Local Device',
          clientName: 'Manual Operator',
          response: nextResponse,
          ussdTranscript: newHistory.map((h) => `[${h.latencyMs}ms] ${h.text}`).join('\n'),
          executionDurationMs: 44,
          source: 'CONSOLE',
          timestamp: Date.now()
        });
      } else {
        setSession({
          ...session,
          currentDialogText: nextResponse,
          step: session.step + 1,
          history: newHistory
        });
        setReplyInput('');
      }
    }, 400);
  };

  const handleCancelSession = () => {
    setSession({
      ...session,
      isActive: false,
      currentDialogText: 'USSD Session Terminated by user.',
      history: [
        ...session.history,
        {
          type: 'out',
          text: 'ACTION_CANCEL_USSD triggered',
          time: new Date().toLocaleTimeString(),
          latencyMs: 5
        }
      ]
    });
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
      {/* Left Column: Dialpad and presets */}
      <div className="lg:col-span-5 space-y-5">
        <div className="bg-[#101725] border border-[#1f2c42] rounded-2xl p-5 space-y-4">
          <div className="flex items-center gap-2">
            <Terminal className="w-5 h-5 text-[#F7A600]" />
            <div>
              <h2 className="text-base font-bold text-white">Manual USSD Dialpad</h2>
              <p className="text-xs text-[#8b9bb4]">Direct access to raw accessibility dial pipeline</p>
            </div>
          </div>

          {/* SIM Selector */}
          <div className="grid grid-cols-2 gap-2">
            <button
              onClick={() => setSelectedSim(1)}
              className={`flex items-center justify-center gap-2 py-2 px-3 rounded-xl border text-xs font-bold transition-all ${
                selectedSim === 1
                  ? 'bg-[#F7A600]/15 border-[#F7A600] text-[#F7A600]'
                  : 'bg-[#090d16] border-[#1f2c42] text-[#8b9bb4] hover:text-white'
              }`}
            >
              <span className="w-2 h-2 rounded-full bg-[#16C784]" />
              SIM 1 (Safaricom)
            </button>
            <button
              onClick={() => setSelectedSim(2)}
              className={`flex items-center justify-center gap-2 py-2 px-3 rounded-xl border text-xs font-bold transition-all ${
                selectedSim === 2
                  ? 'bg-[#F7A600]/15 border-[#F7A600] text-[#F7A600]'
                  : 'bg-[#090d16] border-[#1f2c42] text-[#8b9bb4] hover:text-white'
              }`}
            >
              <span className="w-2 h-2 rounded-full bg-[#a371f7]" />
              SIM 2 (Airtel)
            </button>
          </div>

          {/* Dial Input Field */}
          <div className="relative">
            <input
              type="text"
              value={dialCode}
              onChange={(e) => setDialCode(e.target.value)}
              placeholder="e.g. *144# or *544#"
              className="w-full bg-[#090d16] border border-[#212c3d] rounded-xl px-4 py-3 text-lg font-mono font-bold text-white placeholder-[#6e7f98] focus:outline-none focus:border-[#F7A600]"
            />
            {dialCode && (
              <button
                onClick={() => setDialCode('')}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-[#8b9bb4] hover:text-white"
              >
                <Trash2 className="w-4 h-4" />
              </button>
            )}
          </div>

          {/* Dial Action Button */}
          <button
            onClick={handleDial}
            disabled={isDialing || !dialCode.trim()}
            className="w-full flex items-center justify-center gap-2 py-3 bg-[#16C784] hover:bg-[#13af74] disabled:opacity-50 text-black font-black text-sm rounded-xl shadow-lg transition-transform active:scale-98"
          >
            <Phone className={`w-4 h-4 ${isDialing ? 'animate-bounce' : ''}`} />
            {isDialing ? 'Executing Sub-Frame Dial...' : `Dial on SIM ${selectedSim}`}
          </button>
        </div>

        {/* Quick Presets */}
        <div className="bg-[#101725] border border-[#1f2c42] rounded-2xl p-5 space-y-3">
          <h3 className="text-xs font-bold uppercase tracking-wider text-[#8b9bb4]">
            Kenyan Carrier Shortcode Presets
          </h3>
          <div className="grid grid-cols-2 gap-2">
            {presets.map((preset) => (
              <button
                key={preset.code}
                onClick={() => setDialCode(preset.code)}
                className="p-2.5 bg-[#090d16] hover:bg-[#19161D] border border-[#1f2c42] hover:border-[#F7A600]/40 rounded-xl text-left transition-all"
              >
                <div className="text-xs font-semibold text-white">{preset.label}</div>
                <div className="text-[11px] font-mono text-[#F7A600] font-bold mt-0.5">{preset.code}</div>
              </button>
            ))}
          </div>
        </div>

        {/* Diagnostics Note */}
        <div className="p-4 bg-[#090d16] border border-[#1f2c42] rounded-2xl space-y-2 text-xs">
          <div className="flex items-center gap-2 text-[#16C784] font-bold">
            <CheckCircle2 className="w-4 h-4" />
            Sub-Frame Pipeline Active
          </div>
          <p className="text-[#8b9bb4] leading-relaxed text-[11px]">
            Uses <code className="text-[#58a6ff]">ACTION_IME_ENTER</code> directly for input confirmation. Bounded 1-pass tree refresh avoids accessibility lag on Android 12 / SDK 31 devices.
          </p>
        </div>
      </div>

      {/* Right Column: Interactive Phone Screen / USSD Dialog Simulator */}
      <div className="lg:col-span-7 space-y-5">
        <div className="bg-[#101725] border border-[#1f2c42] rounded-2xl p-5 space-y-4">
          <div className="flex items-center justify-between border-b border-[#1f2c42] pb-3">
            <div className="flex items-center gap-2">
              <span className={`w-2.5 h-2.5 rounded-full ${session.isActive ? 'bg-[#16C784] animate-pulse' : 'bg-[#6e7f98]'}`} />
              <h3 className="text-sm font-bold text-white">Live USSD Session Display</h3>
            </div>
            <span className="text-xs font-mono text-[#8b9bb4]">
              {session.isActive ? `Step ${session.step} Active` : 'No Active Session'}
            </span>
          </div>

          {/* Simulated Android USSD Popup Window */}
          {session.isActive ? (
            <div className="bg-[#090d16] border-2 border-[#F7A600]/50 rounded-2xl p-5 shadow-2xl space-y-4 animate-scaleUp">
              <div className="flex items-center justify-between border-b border-[#1f2c42] pb-2 text-xs">
                <span className="font-mono text-[#F7A600] font-bold">
                  Carrier Dialog (SIM {session.simSlot})
                </span>
                <span className="text-[10px] bg-[#F7A600]/20 text-[#F7A600] px-2 py-0.5 rounded font-mono font-semibold">
                  A11Y Intercept
                </span>
              </div>

              {/* Carrier Dialog Prompt Text */}
              <div className="bg-[#101725] rounded-xl p-4 font-mono text-xs text-[#e6edf3] whitespace-pre-line border border-[#212c3d] leading-relaxed">
                {session.currentDialogText}
              </div>

              {/* Reply Input Box */}
              <div className="space-y-2">
                <label className="text-[11px] font-semibold text-[#8b9bb4] block">
                  Enter Reply Option:
                </label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={replyInput}
                    onChange={(e) => setReplyInput(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleSendReply()}
                    placeholder="Enter choice number (e.g. 1)"
                    autoFocus
                    className="flex-1 bg-[#101725] border border-[#212c3d] rounded-xl px-3 py-2 text-xs font-mono text-white placeholder-[#6e7f98] focus:outline-none focus:border-[#F7A600]"
                  />
                  <button
                    onClick={handleSendReply}
                    disabled={!replyInput.trim()}
                    className="px-4 py-2 bg-[#F7A600] hover:bg-[#e59900] disabled:opacity-50 text-black font-bold text-xs rounded-xl flex items-center gap-1 shadow-md"
                  >
                    <Send className="w-3.5 h-3.5" /> Send
                  </button>
                  <button
                    onClick={handleCancelSession}
                    className="px-3 py-2 bg-[#19161D] hover:bg-[#252029] text-[#f85149] font-semibold text-xs rounded-xl border border-[#3c343a]"
                  >
                    Cancel
                  </button>
                </div>
              </div>

              <div className="text-[10px] font-mono text-[#16C784] flex items-center gap-1 pt-1">
                <Cpu className="w-3 h-3" /> Node Pre-Focus applied (13.2ms rejected IPC eliminated)
              </div>
            </div>
          ) : (
            <div className="bg-[#090d16] border border-[#1f2c42] rounded-2xl p-8 text-center space-y-3">
              <div className="w-12 h-12 mx-auto rounded-full bg-[#19161D] flex items-center justify-center text-[#8b9bb4]">
                <Phone className="w-6 h-6" />
              </div>
              <p className="text-xs text-[#8b9bb4] max-w-sm mx-auto">
                No active USSD session. Enter a shortcode on the left and click Dial to initiate a sub-frame automation session.
              </p>
            </div>
          )}

          {/* Full Session Transcript / Log */}
          <div className="space-y-2 pt-2">
            <h4 className="text-xs font-bold uppercase tracking-wider text-[#8b9bb4]">
              Session Step Log & Latency Breakdown
            </h4>
            <div className="bg-[#090d16] border border-[#1f2c42] rounded-xl p-3 max-h-60 overflow-y-auto font-mono text-[11px] space-y-2">
              {session.history.length === 0 ? (
                <div className="text-[#6e7f98] italic">No transaction transcript recorded yet.</div>
              ) : (
                session.history.map((h, i) => (
                  <div
                    key={i}
                    className={`p-2 rounded-lg ${
                      h.type === 'out'
                        ? 'bg-[#19161D] text-[#58a6ff] border-l-2 border-[#58a6ff]'
                        : 'bg-[#101725] text-[#16C784] border-l-2 border-[#16C784]'
                    }`}
                  >
                    <div className="flex items-center justify-between text-[10px] text-[#8b9bb4] mb-0.5">
                      <span>{h.type === 'out' ? 'OUTBOUND ACTION' : 'INBOUND DIALOG'}</span>
                      <span className="text-[#F7A600] font-semibold">{h.latencyMs}ms</span>
                    </div>
                    <div className="whitespace-pre-line">{h.text}</div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
