// Shared data loader. Exposes a deterministic but VU-distributed view of
// the seeded user list so each VU hits a different row.
//
// users.json is produced by performance/seed-perf-data.sh and contains
// {email, phone, pin, token} per user. Tests use it via:
//
//   import { pickUser, pickRecipient } from './lib/data.js';
//   const me = pickUser(__VU, __ITER);
//
// The token included in users.json was minted at seed time and may be
// expired by the time a test runs against a long-lived environment. The
// auth helper re-logins on demand so this doesn't matter at run time.

import { SharedArray } from 'k6/data';

export const users = new SharedArray('users', () => {
  // Loaded once per test run, shared across all VUs.
  // eslint-disable-next-line no-undef
  return JSON.parse(open('./users.json'));
});

export function pickUser(vu, iter) {
  if (users.length === 0) {
    throw new Error('users.json is empty — run performance/seed-perf-data.sh first');
  }
  // VU/iter spread so neighbouring VUs don't pile on the same row.
  const idx = (vu * 17 + iter * 7) % users.length;
  return users[idx];
}

export function pickRecipient(vu, iter) {
  // Pick a different user as recipient. Adding a stride avoids self-transfer.
  const idx = (vu * 17 + iter * 7 + 1 + (iter % (users.length - 1))) % users.length;
  return users[idx];
}

export function uniqueIdempotencyKey(prefix) {
  // VU-local + iter-local + nanosecond-ish randomness. Used so retries inside
  // the same iteration don't conflict.
  // eslint-disable-next-line no-undef
  return `${prefix}-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}
