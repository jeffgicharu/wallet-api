package com.wallet.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.integration.support.IntegrationTestBase;
import com.wallet.integration.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TransferIntegrationTest extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void shouldDebitSenderCreditReceiverAndChargeOnePercentFeeOnHappyPath() throws Exception {
        String alice = registerAndLogin("alice");
        String bobPhone = TestData.uniquePhone();
        register("bob", bobPhone);
        deposit(alice, 50000);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequest(bobPhone, 5000, "1234")),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(res.getBody());
        assertThat(body.path("data").path("type").asText()).isEqualTo("TRANSFER");

        // Alice: 50000 - 5000 - 50 (1% fee) = 44950
        assertThat(readBalance(alice)).isEqualByComparingTo(new BigDecimal("44950.00"));

        String bob = login("bob");
        assertThat(readBalance(bob)).isEqualByComparingTo(new BigDecimal("5000.00"));

        // 1 CREDIT from the setup deposit + 2 from the transfer (sender DEBIT
        // for amount + fee, receiver CREDIT for amount). The separate FEE
        // Transaction row carries no extra ledger entries in the current
        // implementation — see WalletService line 199 onwards.
        Long ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries", Long.class);
        assertThat(ledgerCount).isEqualTo(3L);

        Long transferLedgerEntries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id IN " +
                "(SELECT id FROM transactions WHERE type = 'TRANSFER')",
                Long.class);
        assertThat(transferLedgerEntries).isEqualTo(2L);

        // Daily-usage updated for the sender
        ResponseEntity<String> info = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, authedHeaders(alice), String.class);
        assertThat(objectMapper.readTree(info.getBody())
                .path("data").path("dailyTransferUsed").asDouble()).isEqualTo(5000.0);
    }

    /**
     * The README claims that retrying with the same idempotency key
     * "returns the original result instead of processing the transfer
     * again." The current implementation rejects the retry with 409
     * (DuplicateTransactionException) instead — which still preserves the
     * key safety property (no double-debit) but is a different shape from
     * the documented behaviour. This test pins the actual behaviour.
     */
    @Test
    void shouldRejectDuplicateIdempotencyKeyAndNotDoubleCharge() throws Exception {
        String alice = registerAndLogin("idem-a");
        String bobPhone = TestData.uniquePhone();
        register("idem-b", bobPhone);
        deposit(alice, 50000);
        String key = TestData.uniqueKey("idem-trf");

        // First call — succeeds
        ResponseEntity<String> first = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequestWithKey(bobPhone, 5000, "1234", key)),
                String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second call with same key — currently rejected as duplicate
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequestWithKey(bobPhone, 5000, "1234", key)),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Critical safety property: no double-debit. Balance reflects exactly
        // ONE transfer of 5000 + 50 fee.
        assertThat(readBalance(alice)).isEqualByComparingTo(new BigDecimal("44950.00"));
    }

    @Test
    void shouldAllowDistinctTransfersWithDifferentIdempotencyKeys() throws Exception {
        String alice = registerAndLogin("dist-a");
        String bobPhone = TestData.uniquePhone();
        register("dist-b", bobPhone);
        deposit(alice, 50000);

        ResponseEntity<String> first = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequest(bobPhone, 1000, "1234")),
                String.class);
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequest(bobPhone, 2000, "1234")),
                String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 50000 - (1000 + 10) - (2000 + 20) = 46970
        assertThat(readBalance(alice)).isEqualByComparingTo(new BigDecimal("46970.00"));
    }

    @Test
    void shouldReturnBadRequestAndNotDebitWhenRecipientDoesNotExist() throws Exception {
        String alice = registerAndLogin("solo");
        deposit(alice, 50000);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequest("+254799000000", 5000, "1234")),
                String.class);

        // Recipient lookup throws IllegalArgumentException → 400 Bad Request
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readBalance(alice)).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    @Test
    void shouldRejectTransferThatExceedsDailyLimitAndNotDebit() throws Exception {
        String alice = registerAndLogin("limit-a");
        String bobPhone = TestData.uniquePhone();
        register("limit-b", bobPhone);
        deposit(alice, 500000);

        // First transfer of 295000 (under the daily limit of 300000)
        ResponseEntity<String> ok = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequest(bobPhone, 295000, "1234")),
                String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

        BigDecimal balanceAfterFirst = readBalance(alice);

        // Second transfer of 6000 would push over the daily limit
        ResponseEntity<String> over = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequest(bobPhone, 6000, "1234")),
                String.class);

        // Daily-limit check throws IllegalArgumentException → 400 Bad Request
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readBalance(alice)).isEqualByComparingTo(balanceAfterFirst);
    }

    @Test
    void shouldRejectTransferWithInsufficientBalance() throws Exception {
        String alice = registerAndLogin("poor-a");
        String bobPhone = TestData.uniquePhone();
        register("poor-b", bobPhone);
        deposit(alice, 100);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequest(bobPhone, 5000, "1234")),
                String.class);

        // InsufficientBalanceException → 400 Bad Request
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readBalance(alice)).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void shouldRejectTransferToSelf() throws Exception {
        String alice = registerAndLogin("self");
        String alicePhone = jdbcTemplate.queryForObject(
                "SELECT phone_number FROM users LIMIT 1", String.class);
        deposit(alice, 50000);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequest(alicePhone, 5000, "1234")),
                String.class);

        // "Cannot transfer to yourself" — IllegalArgumentException → 400
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readBalance(alice)).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    @Test
    void shouldSerialiseConcurrentTransfersFromSameWalletViaOptimisticLocking() throws Exception {
        String alice = registerAndLogin("conc-a");
        String bobPhone = TestData.uniquePhone();
        register("conc-b", bobPhone);
        deposit(alice, 50000);

        int parallel = 5;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        try {
            CompletableFuture<?>[] futures = new CompletableFuture[parallel];
            for (int i = 0; i < parallel; i++) {
                futures[i] = CompletableFuture.runAsync(() -> {
                    ResponseEntity<String> r = restTemplate.exchange(
                            "/api/wallet/transfer", HttpMethod.POST,
                            authedJsonEntity(alice, transferRequest(bobPhone, 1000, "1234")),
                            String.class);
                    if (r.getStatusCode() == HttpStatus.OK) {
                        okCount.incrementAndGet();
                    } else {
                        // Rejection may surface as 409 (mapped OptimisticLockException),
                        // 500 (Spring's wrapper that the current handler doesn't unwrap),
                        // or any non-200. The contract for this test is "at least one
                        // succeeds and the rest fail without corrupting the balance".
                        rejectedCount.incrementAndGet();
                    }
                }, pool);
            }
            CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertThat(okCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(okCount.get() + rejectedCount.get()).isEqualTo(parallel);

        // The crucial invariant: final balance is exactly initial minus
        // okCount full transfers (amount + 1% fee per successful transfer).
        BigDecimal expected = new BigDecimal("50000.00")
                .subtract(new BigDecimal(okCount.get()).multiply(new BigDecimal("1010.00")));
        assertThat(readBalance(alice)).isEqualByComparingTo(expected);
    }

    @Test
    void shouldCalculateOnePercentFeeAcrossDifferentTransferAmounts() throws Exception {
        String alice = registerAndLogin("fee");
        String bobPhone = TestData.uniquePhone();
        register("fee-bob", bobPhone);
        deposit(alice, 200000);

        // Three transfers exercising small / mid / larger amounts. Each
        // assertion compares against the running balance computed from the
        // documented 1 % fee — the test fails loudly if either the fee
        // percentage or the rounding policy ever drifts.
        int[] amounts = {100, 1500, 10000};
        BigDecimal balance = new BigDecimal("200000.00");
        for (int amount : amounts) {
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/wallet/transfer", HttpMethod.POST,
                    authedJsonEntity(alice, transferRequest(bobPhone, amount, "1234")),
                    String.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

            BigDecimal expectedFee = BigDecimal.valueOf(amount)
                    .multiply(new BigDecimal("0.01"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            balance = balance.subtract(BigDecimal.valueOf(amount)).subtract(expectedFee);
            assertThat(readBalance(alice))
                    .as("balance after transfer of %s with fee %s", amount, expectedFee)
                    .isEqualByComparingTo(balance);
        }
    }

    @Test
    void shouldRejectTransferWhenPinIsWrongAndLeaveBalanceUntouched() throws Exception {
        String alice = registerAndLogin("trf-bad-pin");
        String bobPhone = TestData.uniquePhone();
        register("trf-bob", bobPhone);
        deposit(alice, 50000);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, transferRequest(bobPhone, 1000, "9999")),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readBalance(alice)).isEqualByComparingTo(new BigDecimal("50000.00"));
        // The failedPinAttempts increment is rolled back along with the rest
        // of the @Transactional boundary; see WalletOperationsIntegrationTest
        // for the dedicated lockout-bug characterisation.
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private String registerAndLogin(String prefix) throws Exception {
        return registerAndLogin(prefix, TestData.uniquePhone());
    }

    private String registerAndLogin(String prefix, String phone) throws Exception {
        String email = TestData.uniqueEmail(prefix);
        ResponseEntity<String> reg = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(email, phone)),
                String.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(reg.getBody()).path("data").path("token").asText();
    }

    private void register(String prefix, String phone) throws Exception {
        ResponseEntity<String> reg = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(TestData.uniqueEmail(prefix), phone)),
                String.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String login(String emailPrefix) throws Exception {
        String email = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE email LIKE ? LIMIT 1",
                String.class, emailPrefix + "%");
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/auth/login",
                jsonEntity(Map.of("email", email, "password", "password123")),
                String.class);
        return objectMapper.readTree(res.getBody()).path("data").path("token").asText();
    }

    private void deposit(String token, int amount) {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/deposit", HttpMethod.POST,
                authedJsonEntity(token, Map.of(
                        "amount", amount,
                        "idempotencyKey", TestData.uniqueKey("setup-dep"))),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private BigDecimal readBalance(String token) throws Exception {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, authedHeaders(token), String.class);
        return new BigDecimal(
                objectMapper.readTree(res.getBody()).path("data").path("balance").asText());
    }

    private Map<String, Object> transferRequest(String recipientPhone, int amount, String pin) {
        return transferRequestWithKey(recipientPhone, amount, pin, TestData.uniqueKey("trf"));
    }

    private Map<String, Object> transferRequestWithKey(String recipientPhone, int amount, String pin, String key) {
        Map<String, Object> m = new HashMap<>();
        m.put("recipientPhone", recipientPhone);
        m.put("amount", amount);
        m.put("pin", pin);
        m.put("idempotencyKey", key);
        return m;
    }
}
