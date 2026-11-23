import React, { useState } from 'react';
import { 
  Zap, Plus, Search, RotateCcw, Edit3, Trash2, 
  Check, Phone, CheckCircle2, Shield, Radio, Smartphone 
} from 'lucide-react';
import { OfferItem, OfferCategory, ExecutionMode, SimSlot } from '../types';

interface OffersViewProps {
  offers: OfferItem[];
  onToggleOffer: (id: number) => void;
  onAddOffer: (newOffer: Omit<OfferItem, 'id'>) => void;
  onEditOffer: (offer: OfferItem) => void;
  onDeleteOffer: (id: number) => void;
  onResetDefaults: () => void;
  onStartDispatch: (offer: OfferItem) => void;
}

export const OffersView: React.FC<OffersViewProps> = ({
  offers,
  onToggleOffer,
  onAddOffer,
  onEditOffer,
  onDeleteOffer,
  onResetDefaults,
  onStartDispatch
}) => {
  const [selectedCategory, setSelectedCategory] = useState<string>('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [showAddModal, setShowAddModal] = useState(false);
  const [editingOffer, setEditingOffer] = useState<OfferItem | null>(null);

  // Form states for Add/Edit
  const [formName, setFormName] = useState('');
  const [formPrice, setFormPrice] = useState<number>(20);
  const [formUssd, setFormUssd] = useState('*180*5*2*pn*1#');
  const [formCategory, setFormCategory] = useState<OfferCategory>('Data');
  const [formMode, setFormMode] = useState<ExecutionMode>('SIMPLE');
  const [formSim, setFormSim] = useState<SimSlot>(1);

  const openAddModal = () => {
    setEditingOffer(null);
    setFormName('');
    setFormPrice(20);
    setFormUssd('*180*5*2*pn*1#');
    setFormCategory('Data');
    setFormMode('SIMPLE');
    setFormSim(1);
    setShowAddModal(true);
  };

  const openEditModal = (offer: OfferItem) => {
    setEditingOffer(offer);
    setFormName(offer.name);
    setFormPrice(offer.price);
    setFormUssd(offer.ussdCode);
    setFormCategory(offer.category);
    setFormMode(offer.executionMode);
    setFormSim(offer.simSelection);
    setShowAddModal(true);
  };

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    if (!formName.trim() || !formUssd.trim() || formPrice <= 0) return;

    if (editingOffer) {
      onEditOffer({
        ...editingOffer,
        name: formName.trim(),
        price: formPrice,
        ussdCode: formUssd.trim(),
        category: formCategory,
        executionMode: formMode,
        simSelection: formSim
      });
    } else {
      onAddOffer({
        catalogKey: `custom_${Date.now()}`,
        name: formName.trim(),
        price: formPrice,
        ussdCode: formUssd.trim(),
        catalogDefaultUssdCode: formUssd.trim(),
        enabled: true,
        category: formCategory,
        executionMode: formMode,
        simSelection: formSim,
        targetDevice: 'PRIMARY'
      });
    }
    setShowAddModal(false);
  };

  const filteredOffers = offers.filter((o) => {
    const matchesCat = selectedCategory === 'ALL' || o.category === selectedCategory;
    const matchesQuery =
      o.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      o.ussdCode.toLowerCase().includes(searchQuery.toLowerCase()) ||
      o.price.toString().includes(searchQuery);
    return matchesCat && matchesQuery;
  });

  return (
    <div className="space-y-6">
      {/* Top Banner and Actions */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#101725] border border-[#1f2c42] rounded-2xl p-5">
        <div>
          <h2 className="text-xl font-black text-white">USSD Offer Catalog & Bundles</h2>
          <p className="text-xs text-[#8b9bb4] mt-0.5">
            Configure automated dial sequences. Use <code className="text-[#F7A600] font-mono">pn</code> wildcard for automatic recipient phone number replacement.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <button
            onClick={onResetDefaults}
            className="flex items-center gap-1.5 px-3 py-2 rounded-xl bg-[#090d16] hover:bg-[#19161D] text-[#8b9bb4] hover:text-white border border-[#212c3d] text-xs font-semibold transition-all"
            title="Restore official carrier default offers"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            Restore Defaults
          </button>
          <button
            onClick={openAddModal}
            className="flex items-center gap-2 px-4 py-2 bg-[#F7A600] hover:bg-[#e59900] text-black text-xs font-bold rounded-xl shadow-md transition-all active:scale-95"
          >
            <Plus className="w-4 h-4" />
            Add New Offer
          </button>
        </div>
      </div>

      {/* Filter Toolbar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        {/* Category Pills */}
        <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
          {['ALL', 'Data', 'Calls', 'SMS'].map((cat) => (
            <button
              key={cat}
              onClick={() => setSelectedCategory(cat)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition-all ${
                selectedCategory === cat
                  ? 'bg-[#F7A600] text-black shadow'
                  : 'bg-[#101725] text-[#8b9bb4] hover:text-white border border-[#1f2c42]'
              }`}
            >
              {cat === 'ALL' ? 'All Offers' : cat}
            </button>
          ))}
        </div>

        {/* Search */}
        <div className="relative">
          <Search className="w-3.5 h-3.5 text-[#8b9bb4] absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            placeholder="Search offer, dial code, price..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-8 pr-3 py-1.5 bg-[#101725] border border-[#1f2c42] rounded-lg text-xs text-white placeholder-[#6e7f98] focus:outline-none focus:border-[#F7A600] w-full sm:w-64"
          />
        </div>
      </div>

      {/* Offers Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {filteredOffers.map((offer) => {
          return (
            <div
              key={offer.id}
              className={`bg-[#101725] border rounded-2xl p-5 flex flex-col justify-between transition-all relative group ${
                offer.enabled ? 'border-[#1f2c42] hover:border-[#F7A600]/40' : 'border-[#1f2c42]/50 opacity-60'
              }`}
            >
              <div>
                {/* Header row: Category & Status */}
                <div className="flex items-center justify-between gap-2 mb-2">
                  <div className="flex items-center gap-1.5">
                    <span
                      className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase font-mono ${
                        offer.category === 'Data'
                          ? 'bg-[#388bfd]/15 text-[#58a6ff] border border-[#388bfd]/30'
                          : offer.category === 'Calls'
                          ? 'bg-[#a371f7]/15 text-[#d2a8ff] border border-[#a371f7]/30'
                          : 'bg-[#16C784]/15 text-[#16C784] border border-[#16C784]/30'
                      }`}
                    >
                      {offer.category}
                    </span>
                    <span className="px-1.5 py-0.5 rounded text-[10px] font-mono text-[#8b9bb4] bg-[#090d16] border border-[#1f2c42]">
                      {offer.simSelection === 0 ? 'Any SIM' : `SIM ${offer.simSelection}`}
                    </span>
                  </div>

                  {/* Enable/Disable switch */}
                  <button
                    onClick={() => onToggleOffer(offer.id)}
                    className={`w-9 h-5 rounded-full p-0.5 transition-colors ${
                      offer.enabled ? 'bg-[#16C784]' : 'bg-[#2b262a]'
                    }`}
                    title={offer.enabled ? 'Offer enabled' : 'Offer disabled'}
                  >
                    <div
                      className={`w-4 h-4 rounded-full bg-white transition-transform ${
                        offer.enabled ? 'translate-x-4' : 'translate-x-0'
                      }`}
                    />
                  </button>
                </div>

                {/* Title & Price */}
                <div className="mt-1">
                  <h3 className="text-base font-bold text-white group-hover:text-[#F7A600] transition-colors">
                    {offer.name}
                  </h3>
                  <div className="flex items-baseline gap-1 mt-0.5">
                    <span className="text-xs text-[#8b9bb4]">Price:</span>
                    <span className="text-lg font-black text-[#FFD38A] font-mono">
                      Ksh {offer.price}
                    </span>
                  </div>
                </div>

                {/* USSD Code Snippet */}
                <div className="mt-3 bg-[#090d16] border border-[#1f2c42] rounded-xl p-2.5 font-mono text-xs flex items-center justify-between text-[#e6edf3]">
                  <span className="truncate text-[#58a6ff]">{offer.ussdCode}</span>
                  <span className="text-[10px] uppercase font-bold text-[#8b9bb4] ml-2">
                    {offer.executionMode}
                  </span>
                </div>
              </div>

              {/* Actions Footer */}
              <div className="mt-5 pt-3 border-t border-[#1f2c42]/80 flex items-center justify-between gap-2">
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => openEditModal(offer)}
                    className="p-1.5 rounded-lg text-[#8b9bb4] hover:text-white hover:bg-[#19161D] border border-transparent hover:border-[#212c3d]"
                    title="Edit Offer"
                  >
                    <Edit3 className="w-3.5 h-3.5" />
                  </button>
                  <button
                    onClick={() => onDeleteOffer(offer.id)}
                    className="p-1.5 rounded-lg text-[#8b9bb4] hover:text-[#f85149] hover:bg-[#19161D] border border-transparent hover:border-[#212c3d]"
                    title="Delete Offer"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>

                <button
                  onClick={() => onStartDispatch(offer)}
                  disabled={!offer.enabled}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-[#F7A600] hover:bg-[#e59900] disabled:opacity-40 disabled:cursor-not-allowed text-black font-bold text-xs rounded-lg shadow-sm transition-transform active:scale-95"
                >
                  <Zap className="w-3.5 h-3.5" />
                  Dispatch
                </button>
              </div>
            </div>
          );
        })}
      </div>

      {/* Add / Edit Offer Modal */}
      {showAddModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-fadeIn">
          <div className="bg-[#101725] border border-[#212c3d] rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
            <h3 className="text-lg font-bold text-white">
              {editingOffer ? 'Edit USSD Offer' : 'Add Custom USSD Offer'}
            </h3>
            <p className="text-xs text-[#8b9bb4]">
              Specify the carrier dial pattern. The token <code className="text-[#F7A600]">pn</code> will be replaced with recipient MSISDN.
            </p>

            <form onSubmit={handleSave} className="space-y-3.5">
              <div>
                <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">Offer Label</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. 500MB 24 Hours"
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs text-white placeholder-[#6e7f98] focus:outline-none focus:border-[#F7A600]"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">Price (Ksh)</label>
                  <input
                    type="number"
                    min={1}
                    required
                    value={formPrice}
                    onChange={(e) => setFormPrice(Number(e.target.value))}
                    className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs text-white focus:outline-none focus:border-[#F7A600] font-mono"
                  />
                </div>

                <div>
                  <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">Category</label>
                  <select
                    value={formCategory}
                    onChange={(e) => setFormCategory(e.target.value as OfferCategory)}
                    className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs text-white focus:outline-none focus:border-[#F7A600]"
                  >
                    <option value="Data">Data</option>
                    <option value="Calls">Calls</option>
                    <option value="SMS">SMS</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">
                  USSD Dial String (with <span className="text-[#F7A600]">pn</span>)
                </label>
                <input
                  type="text"
                  required
                  placeholder="*180*5*2*pn*6*1#"
                  value={formUssd}
                  onChange={(e) => setFormUssd(e.target.value)}
                  className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs text-[#58a6ff] font-mono focus:outline-none focus:border-[#F7A600]"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">Execution Mode</label>
                  <select
                    value={formMode}
                    onChange={(e) => setFormMode(e.target.value as ExecutionMode)}
                    className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs text-white focus:outline-none focus:border-[#F7A600]"
                  >
                    <option value="SIMPLE">SIMPLE (Direct)</option>
                    <option value="ADVANCED">ADVANCED (Multi-step)</option>
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">SIM Slot Target</label>
                  <select
                    value={formSim}
                    onChange={(e) => setFormSim(Number(e.target.value) as SimSlot)}
                    className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs text-white focus:outline-none focus:border-[#F7A600]"
                  >
                    <option value={0}>General (Default SIM)</option>
                    <option value={1}>SIM 1 (Safaricom)</option>
                    <option value={2}>SIM 2 (Airtel)</option>
                  </select>
                </div>
              </div>

              <div className="flex items-center justify-end gap-2.5 pt-4 border-t border-[#1f2c42]">
                <button
                  type="button"
                  onClick={() => setShowAddModal(false)}
                  className="px-4 py-2 bg-[#090d16] hover:bg-[#19161D] text-[#8b9bb4] text-xs font-semibold rounded-xl border border-[#212c3d]"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 bg-[#F7A600] hover:bg-[#e59900] text-black text-xs font-bold rounded-xl shadow-md"
                >
                  {editingOffer ? 'Save Changes' : 'Create Offer'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
