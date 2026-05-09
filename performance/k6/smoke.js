// Smoke test — sanity check at minimal load. Runs in CI on every PR
// against the docker-compose target, and can be pointed at the live
// origin via TARGET_BASE_URL=https://wallet-api.jeffgicharu.com.
//
// 1 VU, 30 s. Hits the public health endpoint plus one read and one
// write money-moving endpoint to confirm the SLOs are met under no
// contention. Anything failing here means subsequent load / stress
// runs would be uninterpretable.

import http from 'k6/http';
import { sleep, group } from 'k6';
import { slo } from './lib/thresholds.js';
import { recordResponse } from './lib/metrics.js';
import { login, authedHeaders } from './lib/auth.js';
import { uniqueIdempotencyKey } from './lib/data.js';

const BASE_URL = __ENV.TARGET_BASE_URL || 'http://localhost:8080';

// Smoke uses the demo accounts already on the live origin (alice@demo.local)
// and falls back to the seeded perfuser-000 on the local target.
const SMOKE_EMAIL = __ENV.SMOKE_EMAIL || (BASE_URL.includes('jeffgicharu.com')
  ? 'alice@demo.local'
  : 'perfuser-000@perf.local');
const SMOKE_PASSWORD = __ENV.SMOKE_PASSWORD || (BASE_URL.includes('jeffgicharu.com')
  ? 'pass1234'
  : 'password123');

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 1,
      duration: '30s',
    },
  },
  thresholds: slo,
};

export default function () {
  // 1. Health probe — public, lightweight.
  group('health', () => {
    const res = http.get(`${BASE_URL}/actuator/health`, {
      tags: { kind: 'read', endpoint: 'actuator.health' },
    });
    recordResponse(res, 'GET /actuator/health', {
      'health body has UP': (r) => (r.body || '').includes('UP'),
    });
  });

  // 2. Login → carry the JWT through the rest of the iteration.
  let token = null;
  group('login', () => {
    token = login(BASE_URL, SMOKE_EMAIL, SMOKE_PASSWORD);
  });
  if (!token) {
    sleep(1);
    return;
  }
  const headers = authedHeaders(token);

  // 3. One authenticated read.
  group('wallet read', () => {
    const res = http.get(`${BASE_URL}/api/wallet`, {
      headers,
      tags: { kind: 'read', endpoint: 'wallet.get' },
    });
    recordResponse(res, 'GET /api/wallet');
  });

  // 4. One authenticated write — small deposit so the smoke leaves the
  // demo accounts in a recognisable state. Uses a fresh idempotency key
  // each iteration to avoid 409 retry rejections.
  group('wallet deposit', () => {
    const res = http.post(
      `${BASE_URL}/api/wallet/deposit`,
      JSON.stringify({ amount: 10, idempotencyKey: uniqueIdempotencyKey('smoke-dep') }),
      {
        headers,
        tags: { kind: 'write', endpoint: 'wallet.deposit' },
      }
    );
    recordResponse(res, 'POST /api/wallet/deposit');
  });

  sleep(1);
}
