import React, { useState, useEffect } from 'react';
import { Landmark, Shield, Mail, RefreshCw, BarChart3, AlertCircle } from 'lucide-react';
import ThemeToggle from './components/ThemeToggle';
import WalletBalance from './components/WalletBalance';
import TransactionForm from './components/TransactionForm';
import LedgerFeed from './components/LedgerFeed';
import FraudMonitor from './components/FraudMonitor';
import NotificationFeed from './components/NotificationFeed';
import {
  fetchAccounts,
  fetchTransactions,
  fetchFraudLogs,
  fetchNotificationStats,
  fetchDeadNotifications
} from './services/api';

export default function App() {
  const [activeView, setActiveView] = useState('dashboard'); // 'dashboard' | 'fraud' | 'notif'
  const [accounts, setAccounts] = useState([]);
  const [transactions, setTransactions] = useState([]);
  const [fraudLogs, setFraudLogs] = useState([]);
  const [notifStats, setNotifStats] = useState(null);
  const [deadLetters, setDeadLetters] = useState([]);

  const [loading, setLoading] = useState(true);
  const [errorMsg, setErrorMsg] = useState('');

  // Fetch all dashboard data
  const loadDashboardData = async (showLoading = false) => {
    if (showLoading) setLoading(true);
    setErrorMsg('');
    try {
      const [accs, txs, fLogs, nStats, dLetters] = await Promise.all([
        fetchAccounts().catch(e => { console.error(e); return []; }),
        fetchTransactions().catch(e => { console.error(e); return []; }),
        fetchFraudLogs().catch(e => { console.error(e); return []; }),
        fetchNotificationStats().catch(e => { console.error(e); return null; }),
        fetchDeadNotifications().catch(e => { console.error(e); return []; })
      ]);

      setAccounts(accs);
      setTransactions(txs);
      setFraudLogs(fLogs);
      setNotifStats(nStats);
      setDeadLetters(dLetters);
    } catch (err) {
      console.error(err);
      setErrorMsg('Failed to sync dashboard data with the API Gateway. Ensure all backend microservices are running on port 8080.');
    } finally {
      setLoading(false);
    }
  };

  // Initial load and periodic polling
  useEffect(() => {
    loadDashboardData(true);

    // Poll every 3 seconds for live transaction ledger & fraud logs updates
    const interval = setInterval(() => {
      loadDashboardData(false);
    }, 3000);

    return () => clearInterval(interval);
  }, []);

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-[#0b0f19] text-slate-800 dark:text-slate-200 transition-colors duration-200">
      
      {/* Header */}
      <header className="sticky top-0 z-40 w-full border-b border-slate-200/60 dark:border-slate-850 bg-white/80 dark:bg-[#0b0f19]/80 backdrop-blur-md">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-gradient-to-tr from-indigo-600 to-violet-555 text-white shadow-md shadow-indigo-150 dark:shadow-none">
              <Landmark className="w-5 h-5" />
            </div>
            <div>
              <h1 className="text-base font-bold text-slate-900 dark:text-white leading-tight">Smart Wallet</h1>
              <p className="text-[10px] text-slate-400 dark:text-slate-500 font-semibold tracking-wide uppercase">Core Ledger & Monitoring Console</p>
            </div>
          </div>

          <div className="flex items-center gap-4">
            <button
              onClick={() => loadDashboardData(true)}
              disabled={loading}
              className="p-2 rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-400 transition shadow-sm disabled:opacity-50"
              title="Force sync data"
            >
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
            <ThemeToggle />
          </div>
        </div>
      </header>

      {/* Main Body */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-6">
        
        {/* Connection Error Alert */}
        {errorMsg && (
          <div className="flex items-start gap-3 p-4 rounded-2xl bg-rose-50 dark:bg-rose-950/20 text-rose-600 dark:text-rose-400 border border-rose-100 dark:border-rose-900/30">
            <AlertCircle className="w-5 h-5 flex-shrink-0 mt-0.5" />
            <div>
              <p className="text-sm font-bold">API Sync Offline</p>
              <p className="text-xs mt-1 text-rose-500/90">{errorMsg}</p>
            </div>
          </div>
        )}

        {/* Navigation Tabs */}
        <div className="flex border-b border-slate-200 dark:border-slate-850">
          <nav className="flex space-x-6 -mb-px">
            <button
              onClick={() => setActiveView('dashboard')}
              className={`pb-4 px-1 border-b-2 font-semibold text-sm flex items-center gap-2 transition ${
                activeView === 'dashboard'
                  ? 'border-indigo-500 text-indigo-600 dark:text-indigo-400'
                  : 'border-transparent text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-350'
              }`}
            >
              <BarChart3 className="w-4 h-4" />
              Dashboard
            </button>
            <button
              onClick={() => setActiveView('fraud')}
              className={`pb-4 px-1 border-b-2 font-semibold text-sm flex items-center gap-2 transition ${
                activeView === 'fraud'
                  ? 'border-indigo-500 text-indigo-600 dark:text-indigo-400'
                  : 'border-transparent text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-350'
              }`}
            >
              <Shield className="w-4 h-4" />
              Fraud evaluations
            </button>
            <button
              onClick={() => setActiveView('notif')}
              className={`pb-4 px-1 border-b-2 font-semibold text-sm flex items-center gap-2 transition ${
                activeView === 'notif'
                  ? 'border-indigo-500 text-indigo-600 dark:text-indigo-400'
                  : 'border-transparent text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-350'
              }`}
            >
              <Mail className="w-4 h-4" />
              Webhook outbox
            </button>
          </nav>
        </div>

        {/* View Router */}
        <div className="transition-all duration-300">
          {activeView === 'dashboard' && (
            <div className="space-y-6">
              {/* Balances */}
              <WalletBalance accounts={accounts} loading={loading} />

              <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 items-start">
                {/* Submit Console */}
                <div className="lg:col-span-1">
                  <TransactionForm accounts={accounts} onTransactionSuccess={() => loadDashboardData(false)} />
                </div>
                {/* Live Ledger Feed */}
                <div className="lg:col-span-2">
                  <LedgerFeed transactions={transactions} loading={loading} onRefresh={() => loadDashboardData(false)} />
                </div>
              </div>
            </div>
          )}

          {activeView === 'fraud' && (
            <FraudMonitor fraudLogs={fraudLogs} loading={loading} />
          )}

          {activeView === 'notif' && (
            <NotificationFeed stats={notifStats} deadLetters={deadLetters} loading={loading} onRefresh={() => loadDashboardData(false)} />
          )}
        </div>
      </main>

      {/* Footer */}
      <footer className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 mt-12 border-t border-slate-200/40 dark:border-slate-850/40">
        <p className="text-center text-xs text-slate-400 dark:text-slate-600">
          Smart Wallet Dashboard &copy; 2026. Powered by Spring Cloud Gateway, Kafka, and Project Loom.
        </p>
      </footer>
    </div>
  );
}

