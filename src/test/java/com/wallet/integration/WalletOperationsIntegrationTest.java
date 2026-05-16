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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalletOperationsIntegrationTest extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void shouldIncrementBalanceAndWriteLedgerOnDeposit() throws Exception {
        String token = registerAndLogin("dep", TestData.uniquePhone());

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/deposit", HttpMethod.POST,
                authedJsonEntity(token, Map.of(
                        "amount", 25000,
                        "idempotencyKey", TestData.uniqueKey("dep"))),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(res.getBody());
        assertThat(body.path("data").path("type").asText()).isEqualTo("DEPOSIT");
        assertThat(body.path("data").path("status").asText()).isEqualTo("COMPLETED");

        BigDecimal balance = readBalance(token);
        assertThat(balance).isEqualByComparingTo(new BigDecimal("25000.00"));

        // The current implementation writes ONE ledger entry per non-transfer
        // operation (a CREDIT to the wallet on deposit). The system-level
        // counter-entry that would make the ledger fully double-entry is not
        // recorded today; reconciliation accounts for this asymmetry. See
        // ReconciliationIntegrationTest for the matching characterisation.
        Long ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries", Long.class);
        assertThat(ledgerCount).isEqualTo(1L);
    }

    @Test
    void shouldDecrementBalanceAndWriteLedgerOnWithdrawWithCorrectPin() throws Exception {
        String token = registerAndLogin("wdr-ok", TestData.uniquePhone());
        deposit(token, 5000);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/withdraw", HttpMethod.POST,
                authedJsonEntity(token, Map.of(
                        "amount", 1000,
                        "pin", "1234",
                        "idempotencyKey", TestData.uniqueKey("wdr"))),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readBalance(token)).isEqualByComparingTo(new BigDecimal("4000.00"));

        // 1 CREDIT entry from deposit + 1 DEBIT entry from withdraw — see the
        // single-entry note on shouldIncrementBalanceAndWriteLedgerOnDeposit.
        Long ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries", Long.class);
        assertThat(ledgerCount).isEqualTo(2L);
    }

    /**
     * Issue #9 fixed: a wrong PIN is rejected with 401, the balance is
     * unchanged, AND the failed-attempt counter persists (it is now
     * incremented in a REQUIRES_NEW transaction, so the caller's
     * rollback no longer erases it).
     */
    @Test
    void wrongPin_isRejected_andFailedAttemptCounterPersists() throws Exception {
        String token = registerAndLogin("wdr-bad", TestData.uniquePhone());
        deposit(token, 5000);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/withdraw", HttpMethod.POST,
                authedJsonEntity(token, Map.of(
                        "amount", 1000,
                        "pin", "9999",
                        "idempotencyKey", TestData.uniqueKey("wdr-bad"))),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readBalance(token)).isEqualByComparingTo(new BigDecimal("5000.00"));

        // The counter now survives the rolled-back withdraw transaction.
        Integer failedAttempts = jdbcTemplate.queryForObject(
                "SELECT failed_pin_attempts FROM users LIMIT 1", Integer.class);
        assertThat(failedAttempts).isEqualTo(1);
    }

    /**
     * Issue #9 fixed: three wrong PINs trip the 3-strike lockout. The
     * first two attempts are 401 (InvalidPinException); the third
     * persists the counter to 3, sets pin_locked_until, and returns 409
     * (locked). Balance untouched throughout.
     */
    @Test
    void threeWrongPins_triggerLockout() throws Exception {
        String token = registerAndLogin("pin-lock", TestData.uniquePhone());
        deposit(token, 5000);

        for (int i = 1; i <= 3; i++) {
            ResponseEntity<String> r = restTemplate.exchange(
                    "/api/wallet/withdraw", HttpMethod.POST,
                    authedJsonEntity(token, Map.of(
                            "amount", 100,
                            "pin", "9999",
                            "idempotencyKey", TestData.uniqueKey("lock-bad"))),
                    String.class);
            if (i < 3) {
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            } else {
                // 3rd failure locks the account -> 409.
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            }
        }

        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT failed_pin_attempts FROM users LIMIT 1", Integer.class);
        assertThat(attempts).isEqualTo(3);
        Object lockedUntil = jdbcTemplate.queryForObject(
                "SELECT pin_locked_until FROM users LIMIT 1", Object.class);
        assertThat(lockedUntil).isNotNull();

        assertThat(readBalance(token)).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    /**
     * Issue #9 fixed: after the lockout fires, a correct PIN within the
     * 15-minute window is still rejected (409) — the lock holds, money
     * does not move.
     */
    @Test
    void correctPinIsRejectedWhileLocked() throws Exception {
        String token = registerAndLogin("locked-then-good", TestData.uniquePhone());
        deposit(token, 5000);

        // 3 wrong attempts trip the lockout.
        for (int i = 0; i < 3; i++) {
            restTemplate.exchange(
                    "/api/wallet/withdraw", HttpMethod.POST,
                    authedJsonEntity(token, Map.of(
                            "amount", 100,
                            "pin", "9999",
                            "idempotencyKey", TestData.uniqueKey("lockout-trigger"))),
                    String.class);
        }

        // Correct PIN now — still rejected because the account is locked.
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/withdraw", HttpMethod.POST,
                authedJsonEntity(token, Map.of(
                        "amount", 100,
                        "pin", "1234",
                        "idempotencyKey", TestData.uniqueKey("locked-good"))),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(readBalance(token)).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    void shouldReturnBalanceAndDailyLimitUsageOnGetWallet() throws Exception {
        String token = registerAndLogin("info", TestData.uniquePhone());

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, authedHeaders(token), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(res.getBody()).path("data");
        assertThat(data.path("balance").asDouble()).isEqualTo(0.0);
        assertThat(data.path("currency").asText()).isEqualTo("KES");
        assertThat(data.path("dailyTransferLimit").asDouble()).isPositive();
        assertThat(data.path("dailyTransferUsed").asDouble()).isEqualTo(0.0);
        assertThat(data.path("active").asBoolean()).isTrue();
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private String registerAndLogin(String prefix, String phone) throws Exception {
        ResponseEntity<String> reg = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(TestData.uniqueEmail(prefix), phone)),
                String.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(reg.getBody()).path("data").path("token").asText();
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

}
