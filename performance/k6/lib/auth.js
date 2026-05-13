// Authenticates a user via POST /api/auth/login and returns the JWT.
//
// The tokens that ship in users.json are minted at seed time and may have
// expired by the time a long-running stress test starts; this helper
// re-authenticates on demand so VUs always carry a valid bearer token.

import http from 'k6/http';
import { check } from 'k6';

export function login(baseUrl, email, password = 'password123') {
  const res = http.post(
    `${baseUrl}/api/auth/login`,
    JSON.stringify({ email, password }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { kind: 'write', endpoint: 'auth.login' },
    }
  );

  const ok = check(res, {
    'login returned 200': (r) => r.status === 200,
    'login returned a token': (r) => {
      try { return !!r.json('data.token'); } catch { return false; }
    },
  });

  if (!ok) {
    return null;
  }
  return res.json('data.token');
}

export function authedHeaders(token) {
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  };
}
