import React from 'react';
import { MailCheck, MailWarning, MailX, AlertCircle, RefreshCw } from 'lucide-react';

export default function NotificationFeed({ stats, deadLetters, loading, onRefresh }) {
  if (loading && !stats) {
    return (
      <div className="glass rounded-2xl p-6 text-center">
        <p className="text-sm text-slate-500">Loading webhook outbox feed...</p>
      </div>
    );
  }

  const statItems = [
    { label: 'Pending', count: stats?.pending || 0, color: 'text-blue-500 bg-blue-50 dark:bg-blue-950/20' },
    { label: 'Retrying', count: stats?.retrying || 0, color: 'text-amber-500 bg-amber-50 dark:bg-amber-950/20 animate-pulse' },
    { label: 'Sent Webhooks', count: stats?.sent || 0, color: 'text-emerald-500 bg-emerald-50 dark:bg-emerald-950/20' },
    { label: 'Failed', count: stats?.failed || 0, color: 'text-rose-455 bg-rose-50 dark:bg-rose-950/20' },
    { label: 'Dead Letter', count: stats?.dead || 0, color: 'text-slate-600 bg-slate-100 dark:text-slate-400 dark:bg-slate-800' },
  ];

  return (
    <div className="space-y-6">
      {/* Webhook Status Metrics */}
      <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
        {statItems.map((item, idx) => (
          <div key={idx} className="glass rounded-2xl p-4 border border-slate-200/50 dark:border-slate-800/50 flex flex-col justify-between">
            <span className="text-[10px] font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">{item.label}</span>
            <div className="flex items-baseline justify-between mt-2">
              <span className="text-xl font-bold text-slate-800 dark:text-white">{item.count}</span>
              <span className={`w-2 h-2 rounded-full ${
                item.label === 'Sent Webhooks' ? 'bg-emerald-500' :
                item.label === 'Retrying' ? 'bg-amber-500' :
                item.label === 'Pending' ? 'bg-blue-500' :
                item.label === 'Failed' ? 'bg-rose-500' : 'bg-slate-400'
              }`} />
            </div>
          </div>
        ))}
      </div>

      {/* Dead-Letter Alerts */}
      <div className="glass rounded-2xl p-6 shadow-sm border border-slate-200/50 dark:border-slate-800/50">
        <div className="flex justify-between items-center mb-6">
          <div>
            <h2 className="text-lg font-bold text-slate-800 dark:text-white flex items-center gap-2">
              <MailCheck className="w-5 h-5 text-indigo-500" />
              Webhook Outbox Alert Feed
            </h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">Guaranteed at-least-once outbox dispatcher logs & dead letters</p>
          </div>
          <button
            onClick={onRefresh}
            className="p-2 rounded-lg border border-slate-200 dark:border-slate-800 hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-400 transition"
            title="Refresh feed"
          >
            <RefreshCw className="w-4 h-4" />
          </button>
        </div>

        <div className="space-y-4">
          <div>
            <h3 className="text-xs font-bold text-slate-400 dark:text-slate-500 uppercase tracking-wider mb-3">Permanently Failed Webhooks (Dead Letter queue)</h3>
            
            {deadLetters.length === 0 ? (
              <div className="text-center py-6 text-slate-400 dark:text-slate-500 text-xs border border-dashed border-slate-200 dark:border-slate-800 rounded-xl">
                Clean outbox. No dead-letter events require manual intervention.
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left border-collapse text-xs">
                  <thead>
                    <tr className="border-b border-slate-100 dark:border-slate-800 text-slate-400 font-medium">
                      <th className="pb-2">Event Type</th>
                      <th className="pb-2">Transaction ID</th>
                      <th className="pb-2 text-center">Retries</th>
                      <th className="pb-2">Last Error</th>
                      <th className="pb-2">Last Attempt</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100 dark:divide-slate-800/60">
                    {deadLetters.map((dl) => (
                      <tr key={dl.id} className="hover:bg-slate-50/50 dark:hover:bg-slate-900/30">
                        <td className="py-2.5 font-semibold text-rose-600 dark:text-rose-455">
                          {dl.eventType}
                        </td>
                        <td className="py-2.5 font-mono text-slate-500 dark:text-slate-400 select-all">
                          {dl.transactionId}
                        </td>
                        <td className="py-2.5 text-center font-bold text-slate-700 dark:text-slate-300">
                          {dl.retryCount}
                        </td>
                        <td className="py-2.5 text-rose-500 dark:text-rose-400 font-medium max-w-xs truncate" title={dl.lastError}>
                          {dl.lastError}
                        </td>
                        <td className="py-2.5 text-slate-400 font-mono">
                          {dl.lastAttemptedAt ? new Date(dl.lastAttemptedAt).toLocaleTimeString() : 'Never'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
