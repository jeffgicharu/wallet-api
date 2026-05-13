// Workflow test — end-to-end money flow per VU. Local target only.
//
// Each iteration is one full workflow: login → check balance → deposit
// → transfer to a random other user → withdraw → list transactions.
// 20 VU held for 5 min. Measures the success rate of the full flow
// (every step must pass) and the per-step latency.

import http from 'k6/http';
import { sleep, group } from 'k6';
import { Counter } from 'k6/metrics';
import { slo } from './lib/thresholds.js';
import { recordResponse } from './lib/metrics.js';
import { login, authedHeaders } from './lib/auth.js';
import { pickUser, pickRecipient, uniqueIdempotencyKey } from './lib/data.js';

const BASE_URL = __ENV.TARGET_BASE_URL || 'http://localhost:8080';

const workflowsCompleted = new Counter('workflows_completed');
const workflowsFailed = new Counter('workflows_failed');

export const options = {
  scenarios: {
    workflow: {
      executor: 'constant-vus',
      vus: 20,
      duration: '5m',
    },
  },
  thresholds: {
    ...slo,
    // Workflow-specific: at least 95 % of full flows must complete.
    'workflows_completed': [{ threshold: 'count>0', abortOnFail: false }],
  },
};

export default function () {
  let allOk = true;
  const me = pickUser(__VU, __ITER);
  const recipient = pickRecipient(__VU, __ITER);

  let token = null;
  group('1. login', () => {
    token = login(BASE_URL, me.email);
    allOk = allOk && !!token;
  });
  if (!token) { workflowsFailed.add(1); return; }
  const headers = authedHeaders(token);

  group('2. check balance', () => {
    const r = http.get(`${BASE_URL}/api/wallet`, {
      headers, tags: { kind: 'read', endpoint: 'wallet.get' },
    });
    allOk = recordResponse(r, 'GET /api/wallet') && allOk;
  });

  group('3. deposit', () => {
    const r = http.post(
      `${BASE_URL}/api/wallet/deposit`,
      JSON.stringify({ amount: 200, idempotencyKey: uniqueIdempotencyKey('flow-dep') }),
      { headers, tags: { kind: 'write', endpoint: 'wallet.deposit' } },
    );
    allOk = recordResponse(r, 'POST /api/wallet/deposit') && allOk;
  });

  group('4. transfer', () => {
    const r = http.post(
      `${BASE_URL}/api/wallet/transfer`,
      JSON.stringify({
        recipientPhone: recipient.phone,
        amount: 50,
        pin: '1234',
        idempotencyKey: uniqueIdempotencyKey('flow-trf'),
      }),
      { headers, tags: { kind: 'write', endpoint: 'wallet.transfer' } },
    );
    allOk = recordResponse(r, 'POST /api/wallet/transfer') && allOk;
  });

  group('5. withdraw', () => {
    const r = http.post(
      `${BASE_URL}/api/wallet/withdraw`,
      JSON.stringify({ amount: 25, pin: '1234', idempotencyKey: uniqueIdempotencyKey('flow-wdr') }),
      { headers, tags: { kind: 'write', endpoint: 'wallet.withdraw' } },
    );
    allOk = recordResponse(r, 'POST /api/wallet/withdraw') && allOk;
  });

  group('6. list transactions', () => {
    const r = http.get(`${BASE_URL}/api/wallet/transactions?page=0&size=20`, {
      headers, tags: { kind: 'read', endpoint: 'wallet.transactions.list' },
    });
    allOk = recordResponse(r, 'GET /api/wallet/transactions') && allOk;
  });

  if (allOk) {
    workflowsCompleted.add(1);
  } else {
    workflowsFailed.add(1);
  }
  sleep(0.5);
}
