import React, { useState } from 'react';
import { 
  Layers, Zap, CreditCard, Clock, CheckCircle2, 
  Sparkles, Camera, Shield, ArrowUpRight, AlertCircle 
} from 'lucide-react';
import { ActiveUnlimited, UnlimitedPlan } from '../types';
import { UNLIMITED_PLANS } from '../mockData';

interface TokensViewProps {
  tokenBalance: number;
  onAddTokens: (amount: number, costKsh: number) => void;
  activePlan: ActiveUnlimited | null;
  onActivatePlan: (plan: UnlimitedPlan) => void;
  airtimeBalance: number;
  onRechargeAirtime: (pin: string, amountKsh: number) => void;
}

export const TokensView: React.FC<TokensViewProps> = ({
  tokenBalance,
  onAddTokens,
  activePlan,
  onActivatePlan,
  airtimeBalance,
  onRechargeAirtime
}) => {
  const [scratchPin, setScratchPin] = useState('');
  const [isScanning, setIsScanning] = useState(false);
  const [rechargeSuccess, setRechargeSuccess] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const tokenPacks = [
    { tokens: 10, ksh: 50, label: 'Starter Pack', popular: false },
    { tokens: 50, ksh: 200, label: 'Standard Pack', popular: true },
    { tokens: 100, ksh: 350, label: 'Business Pack', popular: false },
    { tokens: 500, ksh: 1500, label: 'Wholesale Pack', popular: false },
  ];

  const handleSimulatedScan = () => {
    setIsScanning(true);
    setErrorMessage(null);
    setRechargeSuccess(null);

    // Simulate OCR text recognition on scratch voucher
    setTimeout(() => {
      setIsScanning(false);
      // Generate realistic 14-digit voucher
      const generatedPin = Array.from({ length: 14 }, () => Math.floor(Math.random() * 10)).join('');
      setScratchPin(generatedPin);
    }, 850);
  };

  const handleRechargePin = (e: React.FormEvent) => {
    e.preventDefault();
    const cleanPin = scratchPin.replace(/\s+/g, '');
    if (cleanPin.length < 10) {
      setErrorMessage('PIN must be at least 10 digits long.');
      return;
    }

    // Determine simulated voucher denomination: Ksh 100, 250, 500 or 1000
    const denominations = [50, 100, 250, 500];
    const creditedAmount = denominations[Math.floor(Math.random() * denominations.length)];

    onRechargeAirtime(cleanPin, creditedAmount);
    setRechargeSuccess(`Successfully recharged Ksh ${creditedAmount} airtime via *141*${cleanPin}#.`);
    setScratchPin('');
    setErrorMessage(null);
  };

  return (
    <div className="space-y-6">
      {/* Top Header Card */}
      <div className="bg-[#101725] border border-[#1f2c42] rounded-2xl p-6">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2 mb-1">
              <span className="p-1 rounded bg-[#F7A600]/20 text-[#F7A600]">
                <Layers className="w-4 h-4" />
              </span>
              <span className="text-xs font-bold uppercase tracking-wider text-[#8b9bb4]">
                Token & Licensing Vault
              </span>
            </div>
            <h2 className="text-2xl font-black text-white">Automation Tokens & Unlimited Passes</h2>
            <p className="text-xs text-[#8b9bb4] mt-1 max-w-2xl">
              Each automated USSD bundle dispatch consumes 1 token. Or activate an Unlimited Pass to dispatch zero-token dispatches with priority accessibility scheduling.
            </p>
          </div>

          <div className="bg-[#090d16] border border-[#1f2c42] rounded-2xl p-4 text-center sm:text-right min-w-[200px]">
            <div className="text-xs text-[#8b9bb4]">Available Balance</div>
            <div className="text-3xl font-black text-[#F7A600] font-mono mt-0.5">
              {tokenBalance} <span className="text-xs text-[#8b9bb4] font-normal">tokens</span>
            </div>
            <div className="text-[11px] text-[#16C784] font-medium mt-1">
              {activePlan ? `Unlimited Pass Active (${activePlan.label})` : 'Pay-As-You-Go Mode'}
            </div>
          </div>
        </div>
      </div>

      {/* Unlimited Passes Section */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-base font-bold text-white flex items-center gap-2">
              <Sparkles className="w-4 h-4 text-[#F7A600]" />
              Unlimited Automation Passes
            </h3>
            <p className="text-xs text-[#8b9bb4]">Bypass per-dispatch token deduction for the selected duration</p>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {UNLIMITED_PLANS.map((plan) => {
            const isCurrentlyActive = activePlan?.planId === plan.id;
            return (
              <div
                key={plan.id}
                className={`bg-[#101725] border rounded-2xl p-5 flex flex-col justify-between transition-all relative ${
                  isCurrentlyActive
                    ? 'border-[#F7A600] shadow-[0_0_20px_rgba(247,166,0,0.15)] bg-gradient-to-b from-[#1c1a24] to-[#101725]'
                    : 'border-[#1f2c42] hover:border-[#388bfd]/50'
                }`}
              >
                <div>
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-mono font-bold text-[#8b9bb4]">
                      {plan.id.replace('unlim_', '').toUpperCase()}
                    </span>
                    {isCurrentlyActive && (
                      <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-[#F7A600] text-black">
                        ACTIVE
                      </span>
                    )}
                  </div>
                  <h4 className="text-sm font-bold text-white">{plan.label}</h4>
                  <div className="text-2xl font-black font-mono text-[#FFD38A] mt-2">
                    Ksh {plan.ksh}
                  </div>
                  <p className="text-[11px] text-[#8b9bb4] mt-1">
                    Zero tokens charged during active period. Multi-SIM routing enabled.
                  </p>
                </div>

                <div className="mt-5 pt-3 border-t border-[#1f2c42]">
                  <button
                    onClick={() => onActivatePlan(plan)}
                    disabled={isCurrentlyActive}
                    className={`w-full py-2 px-3 rounded-xl text-xs font-bold transition-all ${
                      isCurrentlyActive
                        ? 'bg-[#16C784]/20 text-[#16C784] border border-[#16C784]/40 cursor-default'
                        : 'bg-[#F7A600] hover:bg-[#e59900] text-black shadow-md'
                    }`}
                  >
                    {isCurrentlyActive ? 'Plan Currently Active' : `Activate (Ksh ${plan.ksh})`}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Token Packages Top-up */}
      <div className="space-y-3">
        <div>
          <h3 className="text-base font-bold text-white flex items-center gap-2">
            <CreditCard className="w-4 h-4 text-[#58a6ff]" />
            Pay-As-You-Go Token Packages
          </h3>
          <p className="text-xs text-[#8b9bb4]">Purchase bundle tokens using available airtime balance</p>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {tokenPacks.map((pack) => (
            <div
              key={pack.tokens}
              className="bg-[#101725] border border-[#1f2c42] rounded-2xl p-5 flex flex-col justify-between hover:border-[#16C784]/40 transition-all relative"
            >
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-semibold text-[#8b9bb4]">{pack.label}</span>
                  {pack.popular && (
                    <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-[#16C784]/15 text-[#16C784] border border-[#16C784]/30">
                      POPULAR
                    </span>
                  )}
                </div>
                <div className="text-2xl font-black text-white font-mono">
                  {pack.tokens} <span className="text-xs font-normal text-[#8b9bb4]">tokens</span>
                </div>
                <div className="text-base font-bold text-[#FFD38A] font-mono mt-1">
                  Ksh {pack.ksh}
                </div>
                <div className="text-[11px] text-[#8b9bb4] mt-2">
                  {(pack.ksh / pack.tokens).toFixed(1)} Ksh per dispatch
                </div>
              </div>

              <div className="mt-5 pt-3 border-t border-[#1f2c42]">
                <button
                  onClick={() => onAddTokens(pack.tokens, pack.ksh)}
                  className="w-full py-2 px-3 rounded-xl bg-[#090d16] hover:bg-[#16C784] hover:text-black text-white border border-[#212c3d] text-xs font-bold transition-all"
                >
                  Buy with Airtime
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Scratch Card Airtime Recharge Utility */}
      <div className="bg-[#101725] border border-[#1f2c42] rounded-2xl p-6">
        <div className="max-w-2xl space-y-4">
          <div className="flex items-center gap-2.5">
            <div className="p-2 bg-[#16C784]/10 border border-[#16C784]/30 rounded-xl text-[#16C784]">
              <ArrowUpRight className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-bold text-white">Scratch Card Airtime Recharge</h3>
              <p className="text-xs text-[#8b9bb4]">
                Top up SIM airtime via Safaricom/Airtel scratch voucher PIN with instant *141*PIN# automation
              </p>
            </div>
          </div>

          {rechargeSuccess && (
            <div className="p-3 bg-[#16C784]/10 border border-[#16C784]/30 rounded-xl text-xs text-[#16C784] flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 flex-shrink-0" />
              <span>{rechargeSuccess}</span>
            </div>
          )}

          {errorMessage && (
            <div className="p-3 bg-[#f85149]/10 border border-[#f85149]/30 rounded-xl text-xs text-[#f85149] flex items-center gap-2">
              <AlertCircle className="w-4 h-4 flex-shrink-0" />
              <span>{errorMessage}</span>
            </div>
          )}

          <form onSubmit={handleRechargePin} className="space-y-3">
            <div>
              <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">
                Voucher Scratch PIN (12 - 16 digits)
              </label>
              <div className="flex gap-2">
                <input
                  type="text"
                  placeholder="Enter or scan scratch PIN"
                  value={scratchPin}
                  onChange={(e) => setScratchPin(e.target.value)}
                  className="flex-1 bg-[#090d16] border border-[#212c3d] rounded-xl px-4 py-2.5 text-sm font-mono text-white placeholder-[#6e7f98] focus:outline-none focus:border-[#F7A600]"
                />
                <button
                  type="button"
                  onClick={handleSimulatedScan}
                  disabled={isScanning}
                  className="flex items-center gap-1.5 px-3.5 py-2.5 bg-[#19161D] hover:bg-[#252029] text-[#58a6ff] border border-[#3c343a] rounded-xl text-xs font-semibold transition-all"
                  title="Simulate Camera OCR Scan"
                >
                  <Camera className={`w-4 h-4 ${isScanning ? 'animate-spin' : ''}`} />
                  {isScanning ? 'Scanning...' : 'OCR Scan'}
                </button>
              </div>
            </div>

            <div className="flex items-center justify-between pt-2">
              <span className="text-xs text-[#8b9bb4]">
                Current Airtime: <strong className="text-white font-mono">Ksh {airtimeBalance.toFixed(2)}</strong>
              </span>
              <button
                type="submit"
                disabled={!scratchPin.trim()}
                className="px-5 py-2.5 bg-[#16C784] hover:bg-[#13af74] disabled:opacity-50 text-black font-black text-xs rounded-xl shadow-md transition-transform active:scale-95"
              >
                Recharge Airtime (*141*)
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
};
