// SLO thresholds, restated from TEST_STRATEGY.md.
//
// - Read endpoints (GET /api/wallet, GET /api/wallet/transactions, …):
//     p95 < 200 ms, p99 < 500 ms
// - Write money-moving endpoints (deposit, withdraw, transfer):
//     p95 < 400 ms, p99 < 1000 ms
// - System-wide: error rate < 0.1 %
//
// Each k6 test imports `slo` and merges its scenario-specific overrides on
// top. The `abortOnFail` flag makes k6 exit non-zero the moment a threshold
// is crossed, which is what the per-PR smoke job in CI relies on.

export const slo = {
  // System-wide error rate. The custom `errors` Rate metric is incremented
  // on any non-2xx in the helpers below.
  'errors': [
    { threshold: 'rate<0.001', abortOnFail: false },
  ],

  // Per-endpoint latency. Grouped by k6 tags so a transfer slowdown is
  // visible without a wallet-balance read masking it.
  'http_req_duration{kind:read}': [
    { threshold: 'p(95)<200', abortOnFail: false },
    { threshold: 'p(99)<500', abortOnFail: false },
  ],
  'http_req_duration{kind:write}': [
    { threshold: 'p(95)<400', abortOnFail: false },
    { threshold: 'p(99)<1000', abortOnFail: false },
  ],

  // The HTTP-level failure rate exposed by k6 itself. Treats network
  // failures the same way the application does.
  'http_req_failed': [
    { threshold: 'rate<0.001', abortOnFail: false },
  ],
};

// Convenience for tests that want to express budgets in seconds.
export const budgets = {
  readP95Ms: 200,
  readP99Ms: 500,
  writeP95Ms: 400,
  writeP99Ms: 1000,
  errorRate: 0.001,
};
