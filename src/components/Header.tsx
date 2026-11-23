import React from 'react';
import { Smartphone, Zap, Radio, RefreshCw, Terminal, Layers, Users, Activity, Settings } from 'lucide-react';
import { ActiveUnlimited } from '../types';

interface HeaderProps {
  currentTab: string;
  setCurrentTab: (tab: string) => void;
  automationLive: boolean;
  setAutomationLive: (val: boolean) => void;
  airtimeBalance: number;
  tokenBalance: number;
  activePlan: ActiveUnlimited | null;
  onRefreshBalance: () => void;
  isRefreshing: boolean;
}

export const Header: React.FC<HeaderProps> = ({
  currentTab,
  setCurrentTab,
  automationLive,
  setAutomationLive,
  airtimeBalance,
  tokenBalance,
  activePlan,
  onRefreshBalance,
  isRefreshing
}) => {
  const tabs = [
    { id: 'dashboard', label: 'Dashboard', icon: Activity },
    { id: 'offers', label: 'Offers & Catalog', icon: Zap },
    { id: 'terminal', label: 'USSD Terminal', icon: Terminal },
    { id: 'tokens', label: 'Tokens & Plans', icon: Layers },
    { id: 'contacts', label: 'Contacts & Blacklist', icon: Users },
    { id: 'diagnostics', label: 'Setup Doctor', icon: Radio },
    { id: 'settings', label: 'Settings', icon: Settings },
  ];

  return (
    <header className="border-b border-[#212c3d] bg-[#0c1017]/95 backdrop-blur-md sticky top-0 z-40">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        {/* Top bar */}
        <div className="py-4 flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 bg-[#F7A600]/10 border border-[#F7A600]/30 rounded-xl text-[#F7A600] shadow-[0_0_15px_rgba(247,166,0,0.15)]">
              <Smartphone className="w-6 h-6" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-xl sm:text-2xl font-black tracking-tight text-white">Bingwa Mobile</h1>
                <span className="text-[10px] font-mono uppercase px-2 py-0.5 rounded bg-[#F7A600]/15 text-[#F7A600] border border-[#F7A600]/30 font-semibold">
                  v1.1.55 Native
                </span>
              </div>
              <p className="text-xs text-[#8b9bb4]">High-Speed Sub-Frame USSD Engine & Dispatch Daemon</p>
            </div>
          </div>

          {/* Quick Metrics & State */}
          <div className="flex flex-wrap items-center gap-2 sm:gap-3">
            {/* Automation toggle button */}
            <button
              onClick={() => setAutomationLive(!automationLive)}
              className={`flex items-center gap-2 px-3.5 py-1.5 rounded-full text-xs font-bold uppercase tracking-wider border transition-all ${
                automationLive
                  ? 'bg-[#16C784]/15 border-[#16C784]/40 text-[#16C784] hover:bg-[#16C784]/25 shadow-[0_0_12px_rgba(22,199,132,0.2)]'
                  : 'bg-[#f85149]/15 border-[#f85149]/40 text-[#f85149] hover:bg-[#f85149]/25'
              }`}
            >
              <span className={`w-2 h-2 rounded-full ${automationLive ? 'bg-[#16C784] animate-pulse' : 'bg-[#f85149]'}`} />
              {automationLive ? 'Automation Live' : 'Automation Idle'}
            </button>

            {/* Airtime pill */}
            <div className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-[#19161D] border border-[#312B30] text-xs">
              <span className="text-[#8b9bb4]">Airtime:</span>
              <span className="font-mono font-bold text-white">Ksh {airtimeBalance.toFixed(2)}</span>
              <button
                onClick={onRefreshBalance}
                disabled={isRefreshing}
                title="Sync balance via USSD *144#"
                aria-label="Sync balance via USSD *144#"
                className="text-[#8b9bb4] hover:text-[#F7A600] transition-colors disabled:opacity-50"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${isRefreshing ? 'animate-spin text-[#F7A600]' : ''}`} />
              </button>
            </div>

            {/* Tokens / Plan pill */}
            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-[#19161D] border border-[#312B30] text-xs">
              {activePlan ? (
                <div className="flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full bg-[#F7A600] animate-pulse" />
                  <span className="text-[#F7A600] font-semibold">{activePlan.label}</span>
                </div>
              ) : (
                <>
                  <span className="text-[#8b9bb4]">Tokens:</span>
                  <span className="font-mono font-bold text-[#FFD38A]">{tokenBalance}</span>
                </>
              )}
            </div>
          </div>
        </div>

        {/* Navigation tabs */}
        <nav className="flex space-x-1 overflow-x-auto no-scrollbar border-t border-[#212c3d]/60 pt-2 pb-1">
          {tabs.map((tab) => {
            const Icon = tab.icon;
            const active = currentTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setCurrentTab(tab.id)}
                className={`flex items-center gap-2 px-3.5 py-2 text-xs font-semibold rounded-lg whitespace-nowrap transition-all ${
                  active
                    ? 'bg-[#F7A600]/15 text-[#F7A600] border border-[#F7A600]/30 shadow-sm'
                    : 'text-[#8b9bb4] hover:text-white hover:bg-[#19161D]'
                }`}
              >
                <Icon className={`w-4 h-4 ${active ? 'text-[#F7A600]' : 'text-[#8b9bb4]'}`} />
                {tab.label}
              </button>
            );
          })}
        </nav>
      </div>
    </header>
  );
};
