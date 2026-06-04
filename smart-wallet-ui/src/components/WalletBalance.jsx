import React from 'react';
import { Wallet, ShieldAlert, CreditCard, User, Landmark } from 'lucide-react';

export default function WalletBalance({ accounts, loading }) {
  if (loading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 animate-pulse">
        {[1, 2, 3].map(i => (
          <div key={i} className="h-44 rounded-2xl bg-slate-200 dark:bg-slate-800" />
        ))}
      </div>
    );
  }

  if (!accounts || accounts.length === 0) {
    return (
      <div className="glass p-8 rounded-2xl text-center text-slate-500 dark:text-slate-400">
        No accounts found in the database. Start the backend services to seed accounts.
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
      {accounts.map(acc => {
        const isMerchant = acc.accountType === 'MERCHANT';
        const formattedAvailable = new Intl.NumberFormat('en-IN', {
          style: 'currency',
          currency: acc.currency,
        }).format(acc.availableBalance);

        const formattedReserved = new Intl.NumberFormat('en-IN', {
          style: 'currency',
          currency: acc.currency,
        }).format(acc.reservedBalance);

        return (
          <div
            key={acc.id}
            className={`relative overflow-hidden rounded-2xl glass p-6 transition-all duration-300 hover:shadow-lg hover:-translate-y-1 ${
              isMerchant
                ? 'border-l-4 border-l-emerald-500 dark:border-l-emerald-400'
                : 'border-l-4 border-l-indigo-500 dark:border-l-indigo-400'
            }`}
          >
            {/* Background Accent Gradient */}
            <div className={`absolute top-0 right-0 -mr-6 -mt-6 w-24 h-24 rounded-full opacity-10 blur-xl ${
              isMerchant ? 'bg-emerald-500' : 'bg-indigo-500'
            }`} />

            <div className="flex justify-between items-start mb-4">
              <div>
                <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold ${
                  isMerchant 
                    ? 'bg-emerald-100 dark:bg-emerald-950 text-emerald-800 dark:text-emerald-300' 
                    : 'bg-indigo-100 dark:bg-indigo-950 text-indigo-800 dark:text-indigo-300'
                }`}>
                  {isMerchant ? <Landmark className="w-3.5 h-3.5" /> : <CreditCard className="w-3.5 h-3.5" />}
                  {acc.accountType}
                </span>
                <p className="text-xs text-slate-400 dark:text-slate-500 mt-2 font-mono">
                  {acc.accountNumber}
                </p>
              </div>

              <div className={`p-2 rounded-xl ${
                isMerchant ? 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600' : 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600'
              }`}>
                {isMerchant ? <Landmark className="w-5 h-5" /> : <Wallet className="w-5 h-5" />}
              </div>
            </div>

            <div className="space-y-3">
              <div>
                <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Available Balance</p>
                <p className="text-2xl font-bold text-slate-800 dark:text-white tracking-tight">
                  {formattedAvailable}
                </p>
              </div>

              <div className="flex items-center justify-between pt-2 border-t border-slate-100 dark:border-slate-800/60">
                <div>
                  <p className="text-xs font-medium text-slate-400 dark:text-slate-500 flex items-center gap-1">
                    <ShieldAlert className="w-3.5 h-3.5 text-amber-500" />
                    Escrowed/Reserved
                  </p>
                  <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">
                    {formattedReserved}
                  </p>
                </div>

                <div className="text-right">
                  <p className="text-[10px] text-slate-400 dark:text-slate-500 flex items-center justify-end gap-1">
                    <User className="w-3 h-3" />
                    {acc.ownerId}
                  </p>
                  {acc.merchantId && (
                    <p className="text-[10px] text-emerald-600 dark:text-emerald-400 font-medium">
                      MID: {acc.merchantId}
                    </p>
                  )}
                </div>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
