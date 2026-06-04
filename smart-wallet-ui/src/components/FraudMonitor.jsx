import React, { useState } from 'react';
import { ShieldCheck, ShieldAlert, ShieldX, Activity, Sparkles } from 'lucide-react';

export default function FraudMonitor({ fraudLogs, loading }) {
  const [expandedId, setExpandedId] = useState(null);

  const getVerdictIcon = (verdict) => {
    switch (verdict) {
      case 'PASS':
        return <ShieldCheck className="w-5 h-5 text-emerald-500" />;
      case 'FLAG':
        return <ShieldAlert className="w-5 h-5 text-yellow-500" />;
      case 'BLOCK':
        return <ShieldX className="w-5 h-5 text-rose-500" />;
      default:
        return <Activity className="w-5 h-5 text-slate-400" />;
    }
  };

  const getVerdictClass = (verdict) => {
    switch (verdict) {
      case 'PASS':
        return 'text-emerald-600 dark:text-emerald-455 bg-emerald-50 dark:bg-emerald-950/20 border border-emerald-100 dark:border-emerald-900/30';
      case 'FLAG':
        return 'text-yellow-600 dark:text-yellow-455 bg-yellow-50 dark:bg-yellow-950/20 border border-yellow-100 dark:border-yellow-900/30';
      case 'BLOCK':
        return 'text-rose-600 dark:text-rose-455 bg-rose-50 dark:bg-rose-950/20 border border-rose-100 dark:border-rose-900/30';
      default:
        return 'text-slate-600 bg-slate-50 border border-slate-100';
    }
  };

  const parseRules = (rulesJson) => {
    if (!rulesJson) return [];
    if (Array.isArray(rulesJson)) return rulesJson;
    try {
      return JSON.parse(rulesJson);
    } catch {
      return [rulesJson];
    }
  };

  // Metrics calculations
  const totalChecked = fraudLogs.length;
  const blockedCount = fraudLogs.filter(l => l.verdict === 'BLOCK').length;
  const flaggedCount = fraudLogs.filter(l => l.verdict === 'FLAG').length;
  const avgRiskScore = totalChecked 
    ? (fraudLogs.reduce((acc, log) => acc + (log.riskScore || 0), 0) / totalChecked).toFixed(3)
    : '0.000';

  if (loading && fraudLogs.length === 0) {
    return (
      <div className="glass rounded-2xl p-6 text-center">
        <p className="text-sm text-slate-500">Loading fraud evaluations...</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Metrics Banner */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="glass rounded-2xl p-4 border border-slate-200/50 dark:border-slate-800/50">
          <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">Total Evaluated</p>
          <p className="text-xl font-bold text-slate-800 dark:text-white mt-1">{totalChecked}</p>
        </div>
        <div className="glass rounded-2xl p-4 border border-slate-200/50 dark:border-slate-800/50">
          <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">Average Risk Score</p>
          <p className="text-xl font-bold text-indigo-600 dark:text-indigo-400 mt-1">{avgRiskScore}</p>
        </div>
        <div className="glass rounded-2xl p-4 border border-slate-200/50 dark:border-slate-800/50">
          <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">Flagged (Review)</p>
          <p className="text-xl font-bold text-yellow-600 dark:text-yellow-400 mt-1">{flaggedCount}</p>
        </div>
        <div className="glass rounded-2xl p-4 border border-slate-200/50 dark:border-slate-800/50">
          <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">Blocked (Fraud)</p>
          <p className="text-xl font-bold text-rose-600 dark:text-rose-455 mt-1">{blockedCount}</p>
        </div>
      </div>

      {/* Main logs display */}
      <div className="glass rounded-2xl p-6 shadow-sm border border-slate-200/50 dark:border-slate-800/50">
        <div className="flex justify-between items-center mb-6">
          <div>
            <h2 className="text-lg font-bold text-slate-800 dark:text-white flex items-center gap-2">
              <Sparkles className="w-5 h-5 text-indigo-500 animate-pulse" />
              Fraud Evaluation Monitor
            </h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">Real-time risk scores and rules evaluated by the Kafka rule engine</p>
          </div>
        </div>

        <div className="space-y-3">
          {fraudLogs.length === 0 ? (
            <div className="text-center py-8 text-slate-400 dark:text-slate-500 text-sm">
              No fraud evaluation logs found. Initiate transactions to trigger evaluation.
            </div>
          ) : (
            fraudLogs.map(log => {
              const isExpanded = expandedId === log.id;
              const rules = parseRules(log.triggeredRules);
              const evaluatedTime = new Date(log.evaluatedAt).toLocaleTimeString([], {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
              });

              return (
                <div
                  key={log.id}
                  onClick={() => setExpandedId(isExpanded ? null : log.id)}
                  className={`rounded-xl border p-4 transition-all duration-200 cursor-pointer ${
                    isExpanded 
                      ? 'border-indigo-200 bg-indigo-50/10 dark:border-slate-700 dark:bg-slate-900/30' 
                      : 'border-slate-100 dark:border-slate-800 hover:border-slate-200 dark:hover:border-slate-700 bg-white/40 dark:bg-slate-900/10'
                  }`}
                >
                  <div className="flex justify-between items-start gap-3">
                    <div className="flex items-center gap-3">
                      <div className="p-2 rounded-xl bg-white dark:bg-slate-900 border border-slate-100 dark:border-slate-800 shadow-sm flex-shrink-0">
                        {getVerdictIcon(log.verdict)}
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className={`px-2 py-0.5 rounded-md text-[10px] font-bold ${getVerdictClass(log.verdict)}`}>
                            {log.verdict}
                          </span>
                          <span className="text-xs font-semibold text-slate-800 dark:text-slate-200">
                            Tx: {log.transactionId.substring(0, 8)}...
                          </span>
                        </div>
                        <p className="text-[10px] text-slate-400 dark:text-slate-500 font-mono mt-1">
                          Evaluated: {evaluatedTime} | Account: {log.sourceAccountId.substring(0, 8)}...
                        </p>
                      </div>
                    </div>

                    <div className="text-right">
                      <p className="text-[10px] text-slate-400 dark:text-slate-500 font-medium">Risk Score</p>
                      <p className={`text-sm font-bold ${
                        log.riskScore > 0.7 
                          ? 'text-rose-600 dark:text-rose-455' 
                          : log.riskScore > 0.3 
                            ? 'text-amber-600 dark:text-amber-400' 
                            : 'text-emerald-600 dark:text-emerald-455'
                      }`}>
                        {(log.riskScore || 0).toFixed(4)}
                      </p>
                    </div>
                  </div>

                  {/* Expanded block */}
                  {isExpanded && (
                    <div className="mt-4 pt-3 border-t border-slate-100 dark:border-slate-800/80 text-xs space-y-3">
                      <div>
                        <p className="font-semibold text-slate-400 dark:text-slate-500">Analysis Summary</p>
                        <p className="text-slate-700 dark:text-slate-350 mt-1 italic">
                          {log.analysisSummary || 'No high risk signals detected.'}
                        </p>
                      </div>

                      <div>
                        <p className="font-semibold text-slate-400 dark:text-slate-500">Triggered Rules</p>
                        {rules.length === 0 ? (
                          <p className="text-slate-500 mt-1">None (Transaction cleared cleanly)</p>
                        ) : (
                          <div className="flex flex-wrap gap-1.5 mt-1.5">
                            {rules.map((rule, idx) => (
                              <span
                                key={idx}
                                className="px-2 py-0.5 rounded bg-rose-50 dark:bg-rose-950/20 text-rose-700 dark:text-rose-400 border border-rose-100 dark:border-rose-900/30 font-medium text-[10px]"
                              >
                                {rule}
                              </span>
                            ))}
                          </div>
                        )}
                      </div>

                      <div className="grid grid-cols-2 gap-4 pt-1">
                        <div>
                          <p className="font-semibold text-slate-400 dark:text-slate-500">Full Transaction ID</p>
                          <p className="font-mono text-slate-700 dark:text-slate-300 select-all">{log.transactionId}</p>
                        </div>
                        <div>
                          <p className="font-semibold text-slate-400 dark:text-slate-500">Evaluation Log ID</p>
                          <p className="font-mono text-slate-700 dark:text-slate-300 select-all">{log.id}</p>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
