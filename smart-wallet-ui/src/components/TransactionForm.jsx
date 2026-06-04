import React, { useState } from 'react';
import { Send, ArrowUpRight, ShoppingBag, AlertCircle, CheckCircle2 } from 'lucide-react';
import { initiateP2P, initiateWithdrawal, initiateMerchantPayment } from '../services/api';

export default function TransactionForm({ accounts, onTransactionSuccess }) {
  const [activeTab, setActiveTab] = useState('p2p'); // 'p2p' | 'withdraw' | 'merchant'
  
  // Form State
  const [sourceAccountId, setSourceAccountId] = useState('');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('INR');
  
  // P2P Specific State
  const [recipientAccountId, setRecipientAccountId] = useState('');
  const [note, setNote] = useState('');

  // Withdrawal Specific State
  const [destinationBankCode, setDestinationBankCode] = useState('');
  const [instantTransfer, setInstantTransfer] = useState(false);

  // Merchant Specific State
  const [merchantId, setMerchantId] = useState('');
  const [merchantName, setMerchantName] = useState('');
  const [mcc, setMcc] = useState('5411'); // default Grocery mcc

  // Status State
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [successMsg, setSuccessMsg] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!sourceAccountId) {
      setErrorMsg('Please select a source account');
      return;
    }
    if (!amount || parseFloat(amount) <= 0) {
      setErrorMsg('Please enter a valid amount greater than 0');
      return;
    }

    setSubmitting(true);
    setErrorMsg('');
    setSuccessMsg('');

    // Generate random UUID for idempotencyKey and initiatedByUserId
    const idempotencyKey = crypto.randomUUID();
    const initiatedByUserId = 'user-123'; // Default mock user

    try {
      let result;
      if (activeTab === 'p2p') {
        if (!recipientAccountId) throw new Error('Recipient Account ID is required');
        result = await initiateP2P({
          idempotencyKey,
          sourceAccountId,
          recipientAccountId,
          amount: parseFloat(amount),
          currency,
          note,
          initiatedByUserId
        });
      } else if (activeTab === 'withdraw') {
        if (!destinationBankCode) throw new Error('Destination Bank Code is required');
        result = await initiateWithdrawal({
          idempotencyKey,
          sourceAccountId,
          amount: parseFloat(amount),
          currency,
          destinationBankCode,
          instantTransfer,
          initiatedByUserId
        });
      } else {
        if (!merchantId || !merchantName) throw new Error('Merchant details are required');
        result = await initiateMerchantPayment({
          idempotencyKey,
          sourceAccountId,
          amount: parseFloat(amount),
          currency,
          merchantId,
          merchantName,
          mcc: parseInt(mcc),
          initiatedByUserId
        });
      }

      setSuccessMsg(`Transaction initiated successfully! ID: ${result.transactionId}`);
      onTransactionSuccess(); // Refresh balance/ledger
      
      // Reset form fields
      setAmount('');
      setNote('');
      setRecipientAccountId('');
      setDestinationBankCode('');
      setMerchantId('');
      setMerchantName('');
    } catch (err) {
      console.error(err);
      setErrorMsg(err.message || 'An error occurred while initiating the transaction');
    } finally {
      setSubmitting(false);
    }
  };

  const personalAccounts = accounts.filter(acc => acc.accountType === 'PERSONAL');
  const merchantAccounts = accounts.filter(acc => acc.accountType === 'MERCHANT');

  return (
    <div className="glass rounded-2xl p-6 shadow-sm border border-slate-200/50 dark:border-slate-800/50">
      <h2 className="text-lg font-bold text-slate-800 dark:text-white mb-4">Transaction Console</h2>

      {/* Tabs */}
      <div className="flex space-x-1.5 p-1 bg-slate-100 dark:bg-slate-900/60 rounded-xl mb-6">
        <button
          type="button"
          onClick={() => { setActiveTab('p2p'); setErrorMsg(''); setSuccessMsg(''); }}
          className={`flex-1 flex items-center justify-center gap-1.5 py-2 px-3 rounded-lg text-xs font-semibold transition-all duration-200 ${
            activeTab === 'p2p'
              ? 'bg-white dark:bg-slate-800 text-indigo-600 dark:text-indigo-400 shadow-sm'
              : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200'
          }`}
        >
          <Send className="w-3.5 h-3.5" />
          P2P Transfer
        </button>
        <button
          type="button"
          onClick={() => { setActiveTab('withdraw'); setErrorMsg(''); setSuccessMsg(''); }}
          className={`flex-1 flex items-center justify-center gap-1.5 py-2 px-3 rounded-lg text-xs font-semibold transition-all duration-200 ${
            activeTab === 'withdraw'
              ? 'bg-white dark:bg-slate-800 text-indigo-600 dark:text-indigo-400 shadow-sm'
              : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200'
          }`}
        >
          <ArrowUpRight className="w-3.5 h-3.5" />
          Withdrawal
        </button>
        <button
          type="button"
          onClick={() => { setActiveTab('merchant'); setErrorMsg(''); setSuccessMsg(''); }}
          className={`flex-1 flex items-center justify-center gap-1.5 py-2 px-3 rounded-lg text-xs font-semibold transition-all duration-200 ${
            activeTab === 'merchant'
              ? 'bg-white dark:bg-slate-800 text-indigo-600 dark:text-indigo-400 shadow-sm'
              : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200'
          }`}
        >
          <ShoppingBag className="w-3.5 h-3.5" />
          Merchant Pay
        </button>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        {/* Source Account */}
        <div>
          <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">
            Source Account
          </label>
          <select
            value={sourceAccountId}
            onChange={(e) => setSourceAccountId(e.target.value)}
            className="w-full text-sm rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          >
            <option value="">Select Account</option>
            {personalAccounts.map(acc => (
              <option key={acc.id} value={acc.id}>
                {acc.ownerId} - {acc.accountNumber} ({new Intl.NumberFormat('en-IN', { style: 'currency', currency: acc.currency }).format(acc.availableBalance)})
              </option>
            ))}
          </select>
        </div>

        {/* Common fields: Amount and Currency */}
        <div className="grid grid-cols-3 gap-4">
          <div className="col-span-2">
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">
              Amount
            </label>
            <input
              type="number"
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="0.00"
              className="w-full text-sm rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">
              Currency
            </label>
            <select
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              className="w-full text-sm rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="INR">INR</option>
              <option value="USD">USD</option>
            </select>
          </div>
        </div>

        {/* Tab Specific Fields */}
        {activeTab === 'p2p' && (
          <>
            <div>
              <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">
                Recipient Account (or select a seeded merchant)
              </label>
              <select
                value={recipientAccountId}
                onChange={(e) => setRecipientAccountId(e.target.value)}
                className="w-full text-sm rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                <option value="">Select Recipient</option>
                {/* Loop over other personal/merchant accounts for convenience */}
                {accounts.filter(acc => acc.id !== sourceAccountId).map(acc => (
                  <option key={acc.id} value={acc.id}>
                    {acc.ownerId} - {acc.accountNumber} ({acc.accountType})
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">
                Note / Memo
              </label>
              <input
                type="text"
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="Rent, Dinner split, etc."
                className="w-full text-sm rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
          </>
        )}

        {activeTab === 'withdraw' && (
          <>
            <div>
              <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">
                Destination Bank Code (IFSC / BIC)
              </label>
              <input
                type="text"
                value={destinationBankCode}
                onChange={(e) => setDestinationBankCode(e.target.value)}
                placeholder="ICIC0000123"
                className="w-full text-sm rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div className="flex items-center space-x-2 pt-1">
              <input
                type="checkbox"
                id="instant-check"
                checked={instantTransfer}
                onChange={(e) => setInstantTransfer(e.target.checked)}
                className="rounded text-indigo-600 focus:ring-indigo-555"
              />
              <label htmlFor="instant-check" className="text-xs font-semibold text-slate-600 dark:text-slate-300">
                Instant Transfer (IMPS / RTGS)
              </label>
            </div>
          </>
        )}

        {activeTab === 'merchant' && (
          <>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">
                  Merchant Name
                </label>
                <input
                  type="text"
                  value={merchantName}
                  onChange={(e) => {
                    setMerchantName(e.target.value);
                    // Match select logic
                    const match = merchantAccounts.find(m => m.ownerId === e.target.value || m.merchantId === e.target.value);
                    if (match) setMerchantId(match.merchantId);
                  }}
                  placeholder="Amazon, Starbucks"
                  className="w-full text-sm rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">
                  Merchant ID
                </label>
                <select
                  value={merchantId}
                  onChange={(e) => {
                    setMerchantId(e.target.value);
                    const match = merchantAccounts.find(m => m.merchantId === e.target.value);
                    if (match) setMerchantName(match.ownerId);
                  }}
                  className="w-full text-sm rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="">Select MID</option>
                  {merchantAccounts.map(m => (
                    <option key={m.id} value={m.merchantId}>
                      {m.merchantId} ({m.ownerId})
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">
                Merchant Category Code (MCC)
              </label>
              <select
                value={mcc}
                onChange={(e) => setMcc(e.target.value)}
                className="w-full text-sm rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                <option value="5411">Grocery Stores / Supermarkets (5411)</option>
                <option value="5812">Eating Places / Restaurants (5812)</option>
                <option value="6011">ATM Cash Disbursements (6011)</option>
                <option value="7995">Betting/Casino/Gambling - High Risk! (7995)</option>
                <option value="4829">Money Transfer/Wire - Fraud Alert! (4829)</option>
              </select>
            </div>
          </>
        )}

        {/* Feedback Messages */}
        {errorMsg && (
          <div className="flex items-center gap-2 text-xs font-medium p-3 rounded-xl bg-rose-50 dark:bg-rose-950/30 text-rose-600 dark:text-rose-400 border border-rose-100 dark:border-rose-900/30">
            <AlertCircle className="w-4 h-4 flex-shrink-0" />
            <span>{errorMsg}</span>
          </div>
        )}

        {successMsg && (
          <div className="flex items-center gap-2 text-xs font-medium p-3 rounded-xl bg-emerald-50 dark:bg-emerald-950/30 text-emerald-600 dark:text-emerald-400 border border-emerald-100 dark:border-emerald-900/30">
            <CheckCircle2 className="w-4 h-4 flex-shrink-0" />
            <span className="break-all">{successMsg}</span>
          </div>
        )}

        {/* Submit Button */}
        <button
          type="submit"
          disabled={submitting}
          className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-xl text-sm font-semibold text-white bg-indigo-600 hover:bg-indigo-755 disabled:bg-indigo-400 transition-all duration-200 shadow-md shadow-indigo-200 dark:shadow-none hover:shadow-indigo-300 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2"
        >
          {submitting ? (
            <span className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin" />
          ) : (
            'Execute Transaction'
          )}
        </button>
      </form>
    </div>
  );
}
