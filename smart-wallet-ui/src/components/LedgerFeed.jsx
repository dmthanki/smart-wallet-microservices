import React, { useState } from 'react';
import { Clock, CheckCircle2, ShieldAlert, AlertOctagon, ChevronDown, ChevronUp, RefreshCw } from 'lucide-react';

export default function LedgerFeed({ transactions, loading, onRefresh }) {
  const [expandedId, setExpandedId] = useState(null);

  const toggleExpand = (id) => {
    setExpandedId(prev => (prev === id ? null : id));
  };

  const getStatusBadge = (status) => {
    switch (status) {
      case 'PENDING_FRAUD_CHECK':
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-amber-50 dark:bg-amber-950/30 text-amber-600 dark:text-amber-400 border border-amber-250 dark:border-amber-900/30 pending-pulse">
            <Clock className="w-3.5 h-3.5" />
            Fraud Checking
          </span>
        );
      case 'PROCESSED':
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 dark:bg-emerald-950/30 text-emerald-600 dark:text-emerald-400 border border-emerald-250 dark:border-emerald-900/30">
            <CheckCircle2 className="w-3.5 h-3.5" />
            Settled
          </span>
        );
      case 'FRAUD_FLAGGED':
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-yellow-50 dark:bg-yellow-950/30 text-yellow-600 dark:text-yellow-400 border border-yellow-250 dark:border-yellow-900/30">
            <ShieldAlert className="w-3.5 h-3.5" />
            Flagged (Manual Review)
          </span>
        );
      case 'FRAUD_BLOCKED':
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-rose-50 dark:bg-rose-950/30 text-rose-600 dark:text-rose-400 border border-rose-250 dark:border-rose-900/30">
            <AlertOctagon className="w-3.5 h-3.5" />
            Blocked (Fraud)
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-50 dark:bg-slate-900 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-800">
            {status}
          </span>
        );
    }
  };

  const getTransactionTypeLabel = (type) => {
    if (!type) return 'Unknown';
    // If it is a nested object, check properties
    if (type.recipientAccountId) return 'P2P Transfer';
    if (type.destinationBankCode) return 'Withdrawal';
    if (type.sourceReference) return 'Deposit';
    if (type.merchantId) return `Merchant (${type.merchantName || 'Pay'})`;
    return 'Transaction';
  };

  if (loading && transactions.length === 0) {
    return (
      <div className="glass rounded-2xl p-6 text-center">
        <div className="w-8 h-8 border-4 border-indigo-600 border-t-transparent rounded-full animate-spin mx-auto mb-2" />
        <p className="text-sm text-slate-500">Loading ledger feed...</p>
      </div>
    );
  }

  return (
    <div className="glass rounded-2xl p-6 shadow-sm border border-slate-200/50 dark:border-slate-800/50">
      <div className="flex justify-between items-center mb-6">
        <div>
          <h2 className="text-lg font-bold text-slate-800 dark:text-white">Transaction Ledger (Count: {transactions.length}, Loading: {loading ? 'yes' : 'no'})</h2>
          <p className="text-xs text-slate-500 dark:text-slate-400">Live chronological view of transaction state updates</p>
        </div>
        <button
          onClick={onRefresh}
          className="p-2 rounded-lg border border-slate-200 dark:border-slate-800 hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-400 transition"
          title="Refresh ledger"
        >
          <RefreshCw className="w-4 h-4" />
        </button>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="border-b border-slate-100 dark:border-slate-800 text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
              <th className="pb-3 font-medium">Timestamp</th>
              <th className="pb-3 font-medium">Type</th>
              <th className="pb-3 font-medium">Source Acc</th>
              <th className="pb-3 font-medium text-right">Amount</th>
              <th className="pb-3 font-medium text-center">Status</th>
              <th className="pb-3 font-medium w-10"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100/50 dark:divide-slate-800/50 text-sm">
            {transactions.length === 0 ? (
              <tr>
                <td colSpan="6" className="py-8 text-center text-slate-400 dark:text-slate-500">
                  No transactions recorded yet. Submit a transaction above to view execution flow.
                </td>
              </tr>
            ) : (
              transactions.map(tx => {
                const isExpanded = expandedId === tx.transactionId;
                const formattedAmount = new Intl.NumberFormat('en-IN', {
                  style: 'currency',
                  currency: tx.currency,
                }).format(tx.amount);
                
                const timeString = new Date(tx.createdAt).toLocaleTimeString([], {
                  hour: '2-digit',
                  minute: '2-digit',
                  second: '2-digit'
                });

                return (
                  <React.Fragment key={tx.transactionId}>
                    <tr className="hover:bg-slate-50/50 dark:hover:bg-slate-900/30 transition-all">
                      <td className="py-3.5 text-xs text-slate-500 dark:text-slate-400 font-mono">
                        {timeString}
                      </td>
                      <td className="py-3.5 font-semibold text-slate-800 dark:text-slate-200">
                        {getTransactionTypeLabel(tx.type)}
                      </td>
                      <td className="py-3.5 text-xs text-slate-500 dark:text-slate-400 font-mono">
                        {tx.sourceAccountId ? tx.sourceAccountId.substring(0, 8) + '...' : 'System'}
                      </td>
                      <td className="py-3.5 text-right font-bold text-slate-850 dark:text-slate-100">
                        {formattedAmount}
                      </td>
                      <td className="py-3.5 text-center">
                        {getStatusBadge(tx.status)}
                      </td>
                      <td className="py-3.5 text-center">
                        <button
                          onClick={() => toggleExpand(tx.transactionId)}
                          className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
                        >
                          {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                        </button>
                      </td>
                    </tr>
                    
                    {/* Collapsible details row */}
                    {isExpanded && (
                      <tr>
                        <td colSpan="6" className="py-4 px-6 bg-slate-50/70 dark:bg-slate-950/30 rounded-xl">
                          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-xs">
                            <div>
                              <p className="font-semibold text-slate-400 dark:text-slate-500">Transaction ID</p>
                              <p className="font-mono text-slate-700 dark:text-slate-300 select-all">{tx.transactionId}</p>
                            </div>
                            <div>
                              <p className="font-semibold text-slate-400 dark:text-slate-500">Idempotency Key</p>
                              <p className="font-mono text-slate-700 dark:text-slate-300 select-all">{tx.idempotencyKey.substring(0, 18)}...</p>
                            </div>
                            <div>
                              <p className="font-semibold text-slate-400 dark:text-slate-500">Initiated By</p>
                              <p className="text-slate-700 dark:text-slate-300">{tx.initiatedByUserId}</p>
                            </div>
                            <div>
                              <p className="font-semibold text-slate-400 dark:text-slate-500">Processed At</p>
                              <p className="text-slate-700 dark:text-slate-300">
                                {tx.processedAt ? new Date(tx.processedAt).toLocaleString() : 'Pending verification...'}
                              </p>
                            </div>
                          </div>

                          <div className="mt-4 pt-3 border-t border-slate-100 dark:border-slate-850">
                            <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 mb-1">Payload Details (JSON)</p>
                            <pre className="text-xs bg-white dark:bg-slate-900 border border-slate-200/50 dark:border-slate-850 p-2.5 rounded-lg overflow-x-auto font-mono text-indigo-600 dark:text-indigo-400">
                              {JSON.stringify(tx.type, null, 2)}
                            </pre>
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
