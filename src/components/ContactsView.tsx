import React, { useState } from 'react';
import { 
  Users, ShieldAlert, Plus, Search, Trash2, 
  Edit3, Phone, Zap, UserCheck, Shield 
} from 'lucide-react';
import { SavedContact, BlacklistedContact } from '../types';

interface ContactsViewProps {
  contacts: SavedContact[];
  blacklist: BlacklistedContact[];
  onAddContact: (contact: Omit<SavedContact, 'id'>) => void;
  onEditContact: (contact: SavedContact) => void;
  onDeleteContact: (id: string) => void;
  onAddToBlacklist: (entry: Omit<BlacklistedContact, 'id' | 'addedAt'>) => void;
  onRemoveFromBlacklist: (id: string) => void;
  onQuickDispatch: (phone: string, name: string) => void;
}

export const ContactsView: React.FC<ContactsViewProps> = ({
  contacts,
  blacklist,
  onAddContact,
  onEditContact,
  onDeleteContact,
  onAddToBlacklist,
  onRemoveFromBlacklist,
  onQuickDispatch
}) => {
  const [activeSubTab, setActiveSubTab] = useState<'contacts' | 'blacklist'>('contacts');
  const [searchQuery, setSearchQuery] = useState('');

  // Contact form state
  const [showContactModal, setShowContactModal] = useState(false);
  const [editingContact, setEditingContact] = useState<SavedContact | null>(null);
  const [formName, setFormName] = useState('');
  const [formPhone, setFormPhone] = useState('');
  const [formNotes, setFormNotes] = useState('');

  // Blacklist form state
  const [showBlacklistModal, setShowBlacklistModal] = useState(false);
  const [blacklistPhone, setBlacklistPhone] = useState('');
  const [blacklistReason, setBlacklistReason] = useState('');

  const openAddContact = () => {
    setEditingContact(null);
    setFormName('');
    setFormPhone('');
    setFormNotes('');
    setShowContactModal(true);
  };

  const openEditContact = (c: SavedContact) => {
    setEditingContact(c);
    setFormName(c.name);
    setFormPhone(c.phone);
    setFormNotes(c.notes || '');
    setShowContactModal(true);
  };

  const handleSaveContact = (e: React.FormEvent) => {
    e.preventDefault();
    if (!formName.trim() || !formPhone.trim()) return;

    if (editingContact) {
      onEditContact({
        ...editingContact,
        name: formName.trim(),
        phone: formPhone.trim(),
        notes: formNotes.trim()
      });
    } else {
      onAddContact({
        name: formName.trim(),
        phone: formPhone.trim(),
        notes: formNotes.trim()
      });
    }
    setShowContactModal(false);
  };

  const handleSaveBlacklist = (e: React.FormEvent) => {
    e.preventDefault();
    if (!blacklistPhone.trim()) return;
    onAddToBlacklist({
      phone: blacklistPhone.trim(),
      reason: blacklistReason.trim() || 'Manual blacklist entry'
    });
    setBlacklistPhone('');
    setBlacklistReason('');
    setShowBlacklistModal(false);
  };

  const filteredContacts = contacts.filter(
    (c) =>
      c.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      c.phone.includes(searchQuery) ||
      (c.notes && c.notes.toLowerCase().includes(searchQuery.toLowerCase()))
  );

  const filteredBlacklist = blacklist.filter(
    (b) => b.phone.includes(searchQuery) || b.reason.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="space-y-6">
      {/* Header and Sub-tabs */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#101725] border border-[#1f2c42] rounded-2xl p-5">
        <div>
          <h2 className="text-xl font-black text-white">Contacts & Blacklist Manager</h2>
          <p className="text-xs text-[#8b9bb4]">
            Maintain customer address book and manage numbers barred from automated SMS triggers
          </p>
        </div>

        <div className="flex items-center gap-2">
          {activeSubTab === 'contacts' ? (
            <button
              onClick={openAddContact}
              className="flex items-center gap-2 px-4 py-2 bg-[#F7A600] hover:bg-[#e59900] text-black text-xs font-bold rounded-xl shadow-md transition-transform active:scale-95"
            >
              <Plus className="w-4 h-4" />
              Add Contact
            </button>
          ) : (
            <button
              onClick={() => setShowBlacklistModal(true)}
              className="flex items-center gap-2 px-4 py-2 bg-[#f85149] hover:bg-[#e0433c] text-white text-xs font-bold rounded-xl shadow-md transition-transform active:scale-95"
            >
              <Plus className="w-4 h-4" />
              Block Number
            </button>
          )}
        </div>
      </div>

      {/* Tabs and Search Bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <button
            onClick={() => setActiveSubTab('contacts')}
            className={`flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold transition-all ${
              activeSubTab === 'contacts'
                ? 'bg-[#F7A600] text-black shadow'
                : 'bg-[#101725] text-[#8b9bb4] hover:text-white border border-[#1f2c42]'
            }`}
          >
            <Users className="w-4 h-4" />
            Saved Contacts ({contacts.length})
          </button>
          <button
            onClick={() => setActiveSubTab('blacklist')}
            className={`flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold transition-all ${
              activeSubTab === 'blacklist'
                ? 'bg-[#f85149] text-white shadow'
                : 'bg-[#101725] text-[#8b9bb4] hover:text-white border border-[#1f2c42]'
            }`}
          >
            <ShieldAlert className="w-4 h-4" />
            Blacklist ({blacklist.length})
          </button>
        </div>

        <div className="relative">
          <Search className="w-3.5 h-3.5 text-[#8b9bb4] absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            placeholder="Search phone or name..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-8 pr-3 py-1.5 bg-[#101725] border border-[#1f2c42] rounded-lg text-xs text-white placeholder-[#6e7f98] focus:outline-none focus:border-[#F7A600] w-full sm:w-60"
          />
        </div>
      </div>

      {/* Content for Contacts */}
      {activeSubTab === 'contacts' ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filteredContacts.length === 0 ? (
            <div className="col-span-full bg-[#101725] border border-[#1f2c42] rounded-2xl p-8 text-center text-xs text-[#8b9bb4]">
              No contacts found matching search criteria.
            </div>
          ) : (
            filteredContacts.map((contact) => (
              <div
                key={contact.id}
                className="bg-[#101725] border border-[#1f2c42] hover:border-[#F7A600]/40 rounded-2xl p-4 flex flex-col justify-between transition-all group"
              >
                <div>
                  <div className="flex items-center justify-between">
                    <span className="w-8 h-8 rounded-full bg-[#19161D] border border-[#312B30] flex items-center justify-center text-[#F7A600] font-bold text-xs">
                      {contact.name.charAt(0).toUpperCase()}
                    </span>
                    <div className="flex items-center gap-1">
                      <button
                        onClick={() => openEditContact(contact)}
                        className="p-1 rounded text-[#8b9bb4] hover:text-white hover:bg-[#19161D]"
                        title="Edit Contact"
                      >
                        <Edit3 className="w-3.5 h-3.5" />
                      </button>
                      <button
                        onClick={() => onDeleteContact(contact.id)}
                        className="p-1 rounded text-[#8b9bb4] hover:text-[#f85149] hover:bg-[#19161D]"
                        title="Delete Contact"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>

                  <h3 className="text-sm font-bold text-white mt-2 group-hover:text-[#F7A600] transition-colors">
                    {contact.name}
                  </h3>
                  <div className="font-mono text-xs text-[#58a6ff] mt-0.5">{contact.phone}</div>
                  {contact.notes && (
                    <p className="text-[11px] text-[#8b9bb4] mt-2 italic bg-[#090d16] p-2 rounded-lg border border-[#1f2c42]">
                      "{contact.notes}"
                    </p>
                  )}
                </div>

                <div className="mt-4 pt-3 border-t border-[#1f2c42]/80 flex items-center justify-between">
                  <span className="text-[10px] text-[#6e7f98]">
                    {contact.lastUsed ? 'Recently used' : 'Saved'}
                  </span>
                  <button
                    onClick={() => onQuickDispatch(contact.phone, contact.name)}
                    className="flex items-center gap-1.5 px-3 py-1.5 bg-[#F7A600] hover:bg-[#e59900] text-black font-bold text-xs rounded-lg shadow-sm"
                  >
                    <Zap className="w-3 h-3" />
                    Quick Dispatch
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      ) : (
        /* Blacklist Table */
        <div className="bg-[#101725] border border-[#1f2c42] rounded-2xl overflow-hidden shadow-lg">
          <div className="p-4 border-b border-[#1f2c42]">
            <h3 className="text-sm font-bold text-white">Blocked Phone Numbers</h3>
            <p className="text-xs text-[#8b9bb4]">
              Incoming requests or SMS commands from these numbers will be safely rejected (fail-closed)
            </p>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-[#1f2c42] bg-[#090d16]/70 text-[#8b9bb4] font-mono">
                  <th className="py-3 px-4">Phone Number</th>
                  <th className="py-3 px-4">Reason for Block</th>
                  <th className="py-3 px-4">Date Added</th>
                  <th className="py-3 px-4 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#1f2c42]/60">
                {filteredBlacklist.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="text-center py-6 text-[#8b9bb4]">
                      No blacklisted numbers found.
                    </td>
                  </tr>
                ) : (
                  filteredBlacklist.map((entry) => (
                    <tr key={entry.id} className="hover:bg-[#161f2f]/60">
                      <td className="py-3 px-4 font-mono font-bold text-[#f85149]">{entry.phone}</td>
                      <td className="py-3 px-4 text-white">{entry.reason}</td>
                      <td className="py-3 px-4 font-mono text-[#8b9bb4]">
                        {new Date(entry.addedAt).toLocaleDateString()}
                      </td>
                      <td className="py-3 px-4 text-right">
                        <button
                          onClick={() => onRemoveFromBlacklist(entry.id)}
                          className="px-2.5 py-1 bg-[#19161D] hover:bg-[#f85149]/20 text-[#f85149] border border-[#3c343a] rounded-lg text-xs font-semibold transition-all"
                        >
                          Unblock
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Contact Modal */}
      {showContactModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-fadeIn">
          <div className="bg-[#101725] border border-[#212c3d] rounded-2xl max-w-sm w-full p-6 space-y-4 shadow-2xl">
            <h3 className="text-lg font-bold text-white">
              {editingContact ? 'Edit Contact' : 'Add New Contact'}
            </h3>
            <form onSubmit={handleSaveContact} className="space-y-3">
              <div>
                <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">Contact Name</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Kiprono Davis"
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs text-white focus:outline-none focus:border-[#F7A600]"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">Phone Number</label>
                <input
                  type="tel"
                  required
                  placeholder="0712345678"
                  value={formPhone}
                  onChange={(e) => setFormPhone(e.target.value)}
                  className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs font-mono text-white focus:outline-none focus:border-[#F7A600]"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">Notes / Category</label>
                <input
                  type="text"
                  placeholder="e.g. Wholesale client"
                  value={formNotes}
                  onChange={(e) => setFormNotes(e.target.value)}
                  className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs text-white focus:outline-none focus:border-[#F7A600]"
                />
              </div>

              <div className="flex items-center justify-end gap-2.5 pt-3 border-t border-[#1f2c42]">
                <button
                  type="button"
                  onClick={() => setShowContactModal(false)}
                  className="px-4 py-2 bg-[#090d16] text-[#8b9bb4] text-xs font-semibold rounded-xl border border-[#212c3d]"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 bg-[#F7A600] text-black text-xs font-bold rounded-xl shadow-md"
                >
                  Save
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Blacklist Modal */}
      {showBlacklistModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-fadeIn">
          <div className="bg-[#101725] border border-[#212c3d] rounded-2xl max-w-sm w-full p-6 space-y-4 shadow-2xl">
            <h3 className="text-lg font-bold text-white flex items-center gap-2">
              <ShieldAlert className="w-5 h-5 text-[#f85149]" />
              Block Phone Number
            </h3>
            <form onSubmit={handleSaveBlacklist} className="space-y-3">
              <div>
                <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">Phone Number</label>
                <input
                  type="tel"
                  required
                  placeholder="0799999999"
                  value={blacklistPhone}
                  onChange={(e) => setBlacklistPhone(e.target.value)}
                  className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs font-mono text-white focus:outline-none focus:border-[#f85149]"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-[#8b9bb4] mb-1">Reason for Blocking</label>
                <input
                  type="text"
                  placeholder="e.g. Payment reversal dispute"
                  value={blacklistReason}
                  onChange={(e) => setBlacklistReason(e.target.value)}
                  className="w-full px-3 py-2 bg-[#090d16] border border-[#212c3d] rounded-xl text-xs text-white focus:outline-none focus:border-[#f85149]"
                />
              </div>

              <div className="flex items-center justify-end gap-2.5 pt-3 border-t border-[#1f2c42]">
                <button
                  type="button"
                  onClick={() => setShowBlacklistModal(false)}
                  className="px-4 py-2 bg-[#090d16] text-[#8b9bb4] text-xs font-semibold rounded-xl border border-[#212c3d]"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 bg-[#f85149] text-white text-xs font-bold rounded-xl shadow-md"
                >
                  Block Number
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
