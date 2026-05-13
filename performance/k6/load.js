// Load test — sustained nominal traffic. Local target only.
//
// Ramp 0 → 50 VU over 1 min, hold 5 min at 50 VU, ramp down. Traffic
// distribution mirrors realistic frontend patterns:
//   65 % reads (wallet balance + transactions list + transactions lookup)
//   30 % mixed-write (deposit + withdraw)
//    5 % pure-write (transfer — the highest-complexity endpoint)
//
// Each VU uses a different seeded user so the load is spread across
// rows, not piled on the alice/bob hot pair.

import http from 'k6/http';
import { sleep, group } from 'k6';
import { slo } from './lib/thresholds.js';
import { recordResponse } from './lib/metrics.js';
import { login, authedHeaders } from './lib/auth.js';
import { pickUser, pickRecipient, uniqueIdempotencyKey } from './lib/data.js';

const BASE_URL = __ENV.TARGET_BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 50 },
        { duration: '5m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: slo,
};

export default function () {
  const me = pickUser(__VU, __ITER);
  const token = login(BASE_URL, me.email);
  if (!token) { sleep(1); return; }
  const headers = authedHeaders(token);

  // Roll a percentage to decide the action this iteration represents.
  const dice = Math.random() * 100;

  if (dice < 65) {
    // 65 % reads
    if (Math.random() < 0.6) {
      group('wallet balance', () => {
        const r = http.get(`${BASE_URL}/api/wallet`, {
          headers, tags: { kind: 'read', endpoint: 'wallet.get' },
        });
        recordResponse(r, 'GET /api/wallet');
      });
    } else {
      group('transactions list', () => {
        const r = http.get(`${BASE_URL}/api/wallet/transactions?page=0&size=20`, {
          headers, tags: { kind: 'read', endpoint: 'wallet.transactions.list' },
        });
        recordResponse(r, 'GET /api/wallet/transactions');
      });
    }
  } else if (dice < 95) {
    // 30 % mixed write — deposit / withdraw
    if (Math.random() < 0.5) {
      group('deposit', () => {
        const r = http.post(
          `${BASE_URL}/api/wallet/deposit`,
          JSON.stringify({ amount: 50, idempotencyKey: uniqueIdempotencyKey('load-dep') }),
          { headers, tags: { kind: 'write', endpoint: 'wallet.deposit' } },
        );
        recordResponse(r, 'POST /api/wallet/deposit');
      });
    } else {
      group('withdraw', () => {
        const r = http.post(
          `${BASE_URL}/api/wallet/withdraw`,
          JSON.stringify({ amount: 25, pin: '1234', idempotencyKey: uniqueIdempotencyKey('load-wdr') }),
          { headers, tags: { kind: 'write', endpoint: 'wallet.withdraw' } },
        );
        recordResponse(r, 'POST /api/wallet/withdraw');
      });
    }
  } else {
    // 5 % transfer
    const recipient = pickRecipient(__VU, __ITER);
    group('transfer', () => {
      const r = http.post(
        `${BASE_URL}/api/wallet/transfer`,
        JSON.stringify({
          recipientPhone: recipient.phone,
          amount: 50,
          pin: '1234',
          idempotencyKey: uniqueIdempotencyKey('load-trf'),
        }),
        { headers, tags: { kind: 'write', endpoint: 'wallet.transfer' } },
      );
      recordResponse(r, 'POST /api/wallet/transfer');
    });
  }

  sleep(0.5);
}
