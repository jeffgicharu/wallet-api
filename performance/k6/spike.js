// Spike test — sudden traffic surge. Local target only.
//
// 10 VU baseline → spike to 300 VU for 1 min → back to 10 VU.
// Reads recovery time after the spike: how long until p95 drops back
// under the read SLO.

import http from 'k6/http';
import { sleep } from 'k6';
import { slo } from './lib/thresholds.js';
import { recordResponse } from './lib/metrics.js';
import { login, authedHeaders } from './lib/auth.js';
import { pickUser, pickRecipient, uniqueIdempotencyKey } from './lib/data.js';

const BASE_URL = __ENV.TARGET_BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '20s', target: 300 },
        { duration: '1m',  target: 300 },
        { duration: '20s', target: 10 },
        { duration: '1m',  target: 10 },
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

  const dice = Math.random() * 100;

  if (dice < 65) {
    const r = http.get(`${BASE_URL}/api/wallet`, {
      headers, tags: { kind: 'read', endpoint: 'wallet.get' },
    });
    recordResponse(r, 'GET /api/wallet');
  } else if (dice < 95) {
    const r = http.post(
      `${BASE_URL}/api/wallet/deposit`,
      JSON.stringify({ amount: 50, idempotencyKey: uniqueIdempotencyKey('spike-dep') }),
      { headers, tags: { kind: 'write', endpoint: 'wallet.deposit' } },
    );
    recordResponse(r, 'POST /api/wallet/deposit');
  } else {
    const recipient = pickRecipient(__VU, __ITER);
    const r = http.post(
      `${BASE_URL}/api/wallet/transfer`,
      JSON.stringify({
        recipientPhone: recipient.phone,
        amount: 50,
        pin: '1234',
        idempotencyKey: uniqueIdempotencyKey('spike-trf'),
      }),
      { headers, tags: { kind: 'write', endpoint: 'wallet.transfer' } },
    );
    recordResponse(r, 'POST /api/wallet/transfer');
  }
}
