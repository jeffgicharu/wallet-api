// Custom metrics shared across the perf scripts.
//
// k6's built-in `http_req_duration` is split by tags (kind=read|write,
// endpoint=auth.login|wallet.balance|…) so we can compare per-endpoint
// percentiles without writing a metric per endpoint. The `errors` Rate
// is incremented on any non-2xx — used by the threshold rules in
// thresholds.js.

import { Rate } from 'k6/metrics';
import { check } from 'k6';

export const errorRate = new Rate('errors');

/**
 * Records the response in `errorRate` based on a 2xx check, and runs
 * any extra checks the caller passes in. Returns true iff the response
 * passed every check.
 */
export function recordResponse(res, label, extraChecks = {}) {
  const checks = {
    [`${label} status is 2xx`]: (r) => r.status >= 200 && r.status < 300,
    ...extraChecks,
  };
  const passed = check(res, checks);
  errorRate.add(!passed);
  return passed;
}

/**
 * Same as above but for endpoints where a non-2xx is expected behaviour
 * (e.g. login with the wrong password). Records as success when the
 * actual status matches the expected.
 */
export function recordExpectedStatus(res, expectedStatus, label) {
  const passed = check(res, {
    [`${label} status is ${expectedStatus}`]: (r) => r.status === expectedStatus,
  });
  errorRate.add(!passed);
  return passed;
}
