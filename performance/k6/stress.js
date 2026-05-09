// Stress test — find the breaking point. Local target only.
//
// Ramp 0 → 200 VU over 5 min, hold 5 min at 200 VU, ramp down 5 min.
// Same traffic distribution as load.js. The output is read for the
// inflection point where p95/p99 explodes or error rate climbs above
// the 0.1 % budget — that's the breaking point reported in
// PERFORMANCE_TESTING.md.

import http from 'k6/http';
import { sleep, group } from 'k6';
import { slo } from './lib/thresholds.js';
import { recordResponse } from './lib/metrics.js';
import { login, authedHeaders } from './lib/auth.js';
import { pickUser, pickRecipient, uniqueIdempotencyKey } from './lib/data.js';

const BASE_URL = __ENV.TARGET_BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5m', target: 200 },
        { duration: '5m', target: 200 },
        { duration: '5m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: slo,
};

export default function () {
  const me = pickUser(__VU, __ITER);
  const token = login(BASE_URL, me.email);
  if (!token) { sleep(1); return; }
  const headers = authedHeaders(token);

  const dice = Math.random() * 100;

  if (dice < 65) {
    if (Math.random() < 0.6) {
      const r = http.get(`${BASE_URL}/api/wallet`, {
        headers, tags: { kind: 'read', endpoint: 'wallet.get' },
      });
      recordResponse(r, 'GET /api/wallet');
    } else {
      const r = http.get(`${BASE_URL}/api/wallet/transactions?page=0&size=20`, {
        headers, tags: { kind: 'read', endpoint: 'wallet.transactions.list' },
      });
      recordResponse(r, 'GET /api/wallet/transactions');
    }
  } else if (dice < 95) {
    if (Math.random() < 0.5) {
      const r = http.post(
        `${BASE_URL}/api/wallet/deposit`,
        JSON.stringify({ amount: 50, idempotencyKey: uniqueIdempotencyKey('stress-dep') }),
        { headers, tags: { kind: 'write', endpoint: 'wallet.deposit' } },
      );
      recordResponse(r, 'POST /api/wallet/deposit');
    } else {
      const r = http.post(
        `${BASE_URL}/api/wallet/withdraw`,
        JSON.stringify({ amount: 25, pin: '1234', idempotencyKey: uniqueIdempotencyKey('stress-wdr') }),
        { headers, tags: { kind: 'write', endpoint: 'wallet.withdraw' } },
      );
      recordResponse(r, 'POST /api/wallet/withdraw');
    }
  } else {
    const recipient = pickRecipient(__VU, __ITER);
    const r = http.post(
      `${BASE_URL}/api/wallet/transfer`,
      JSON.stringify({
        recipientPhone: recipient.phone,
        amount: 50,
        pin: '1234',
        idempotencyKey: uniqueIdempotencyKey('stress-trf'),
      }),
      { headers, tags: { kind: 'write', endpoint: 'wallet.transfer' } },
    );
    recordResponse(r, 'POST /api/wallet/transfer');
  }

  // No deliberate sleep — stress runs at full tilt to find saturation.
}
