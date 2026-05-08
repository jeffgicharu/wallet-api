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
     * Characterises the current behaviour of wrong-PIN handling. The README
     * promises a 3-strike lockout; in practice
     * {@code WalletService.validatePin} increments {@code failedPinAttempts}
     * and then throws — the surrounding {@code @Transactional} on
     * {@code withdraw} / {@code transfer} sees the {@code RuntimeException}
     * and rolls the increment back. The lockout therefore never fires.
     *
     * <p>This test pins the actual behaviour: the request is rejected with
     * 401, the balance is unchanged, and {@code failed_pin_attempts} reads as
     * 0 (rolled back). When the lockout is fixed, this test is the regression
     * net — it will fail and force the new expectation to be written down.
     */
    @Test
    void issueCharacterisation_wrongPinIsRejectedButFailedAttemptsCounterIsNotPersisted() throws Exception {
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

        // Current behaviour (rolled back). When the lockout is fixed this
        // becomes 1, then 2, then 3 with a non-null pinLockedUntil.
        Integer failedAttempts = jdbcTemplate.queryForObject(
                "SELECT failed_pin_attempts FROM users LIMIT 1", Integer.class);
        assertThat(failedAttempts).isEqualTo(0);
    }

    /**
     * Characterises the current behaviour of repeated wrong-PIN attempts. The
     * READMEs documents a 3-strike lockout; in practice every attempt returns
     * 401 because the {@code @Transactional} rollback in
     * {@code WalletService} undoes the
     * {@code failedPinAttempts}-increment that
     * {@code validatePin} performed before throwing.
     *
     * <p>Once the bug is fixed (likely by moving the counter persistence to a
     * {@code REQUIRES_NEW} transaction or out of the rollback path) the third
     * attempt will return 409 instead of 401, and this test flips.
     */
    @Test
    void issueCharacterisation_repeatedWrongPinAttemptsCurrentlyDoNotTriggerLockout() throws Exception {
        String token = registerAndLogin("pin-lock", TestData.uniquePhone());
        deposit(token, 5000);

        // Three wrong attempts in a row: every single one currently returns 401.
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> r = restTemplate.exchange(
                    "/api/wallet/withdraw", HttpMethod.POST,
                    authedJsonEntity(token, Map.of(
                            "amount", 100,
                            "pin", "9999",
                            "idempotencyKey", TestData.uniqueKey("lock-bad"))),
                    String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // Counter and lockedUntil never persist.
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT failed_pin_attempts FROM users LIMIT 1", Integer.class);
        assertThat(attempts).isEqualTo(0);
        Object lockedUntil = jdbcTemplate.queryForObject(
                "SELECT pin_locked_until FROM users LIMIT 1", Object.class);
        assertThat(lockedUntil).isNull();

        // Balance is intact — nothing was actually withdrawn.
        assertThat(readBalance(token)).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    /**
     * Characterises the consequence of the lockout bug above: because the
     * lockedUntil never persists, a correct PIN immediately after several
     * wrong attempts goes through. When the lockout is fixed this becomes
     * a 409 Conflict.
     */
    @Test
    void issueCharacterisation_correctPinSucceedsImmediatelyAfterWrongAttempts() throws Exception {
        String token = registerAndLogin("locked-then-good", TestData.uniquePhone());
        deposit(token, 5000);

        // 3 wrong attempts (no lockout fires)
        for (int i = 0; i < 3; i++) {
            restTemplate.exchange(
                    "/api/wallet/withdraw", HttpMethod.POST,
                    authedJsonEntity(token, Map.of(
                            "amount", 100,
                            "pin", "9999",
                            "idempotencyKey", TestData.uniqueKey("lockout-trigger"))),
                    String.class);
        }

        // Correct PIN — currently goes through. Once the lockout is fixed this
        // becomes 409 with the lockedUntil-window message.
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/withdraw", HttpMethod.POST,
                authedJsonEntity(token, Map.of(
                        "amount", 100,
                        "pin", "1234",
                        "idempotencyKey", TestData.uniqueKey("locked-good"))),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readBalance(token)).isEqualByComparingTo(new BigDecimal("4900.00"));
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
