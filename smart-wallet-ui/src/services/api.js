/**
 * API Service Client for Smart Wallet UI Dashboard
 * Communicates with the backend API Gateway (proxied through Vite to port 8080)
 */

export async function fetchAccounts() {
  const res = await fetch('/api/v1/transactions/accounts');
  if (!res.ok) throw new Error(`Failed to fetch accounts: ${res.statusText}`);
  return res.json();
}

export async function fetchTransactions() {
  const res = await fetch('/api/v1/transactions');
  if (!res.ok) throw new Error(`Failed to fetch transactions: ${res.statusText}`);
  return res.json();
}

export async function initiateP2P(data) {
  const res = await fetch('/api/v1/transactions/p2p', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) {
    const errText = await res.text();
    throw new Error(errText || 'Failed to initiate P2P transaction');
  }
  return res.json();
}

export async function initiateWithdrawal(data) {
  const res = await fetch('/api/v1/transactions/withdraw', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) {
    const errText = await res.text();
    throw new Error(errText || 'Failed to initiate withdrawal');
  }
  return res.json();
}

export async function initiateMerchantPayment(data) {
  const res = await fetch('/api/v1/transactions/merchant-pay', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) {
    const errText = await res.text();
    throw new Error(errText || 'Failed to initiate merchant payment');
  }
  return res.json();
}

export async function fetchFraudLogs() {
  const res = await fetch('/api/v1/fraud/logs');
  if (!res.ok) throw new Error(`Failed to fetch fraud logs: ${res.statusText}`);
  return res.json();
}

export async function fetchNotificationStats() {
  const res = await fetch('/api/v1/notifications/outbox/stats');
  if (!res.ok) throw new Error(`Failed to fetch notification stats: ${res.statusText}`);
  return res.json();
}

export async function fetchDeadNotifications() {
  const res = await fetch('/api/v1/notifications/outbox/dead');
  if (!res.ok) throw new Error(`Failed to fetch dead notifications: ${res.statusText}`);
  return res.json();
}
