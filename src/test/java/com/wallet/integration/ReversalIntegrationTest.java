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

import static org.assertj.core.api.Assertions.assertThat;

class ReversalIntegrationTest extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void shouldRefundSenderAndCreateCompensatingLedgerEntriesWhenReversingDeposit() throws Exception {
        String alice = registerAndLogin("rev-dep");
        String depositRef = deposit(alice, 8000);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transactions/" + depositRef + "/reverse?reason=customer-request",
                HttpMethod.POST, authedHeaders(alice), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readBalance(alice)).isEqualByComparingTo(new BigDecimal("0.00"));

        // Original deposit is now REVERSED; a new REV-prefixed transaction
        // captures the reversal. The reversal row carries the original
        // type (DEPOSIT) — see WalletService.reverseTransaction line 304.
        Long reversedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE status = 'REVERSED'", Long.class);
        Long reversalRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reference LIKE 'REV-%'", Long.class);
        assertThat(reversedCount).isEqualTo(1L);
        assertThat(reversalRowCount).isEqualTo(1L);

        // Issue #11: deposit = wallet CREDIT + system DEBIT (2);
        // reversal = wallet DEBIT + system CREDIT (2). 4 total, books
        // balanced.
        Long ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries", Long.class);
        assertThat(ledgerCount).isEqualTo(4L);
    }

    @Test
    void shouldRefundSenderIncludingFeeAndDebitReceiverWhenReversingTransfer() throws Exception {
        String alice = registerAndLogin("rev-trf-a");
        String bobPhone = TestData.uniquePhone();
        register("rev-trf-b", bobPhone);
        deposit(alice, 50000);

        // Alice → Bob 5000; fee 50; Alice 44950, Bob 5000
        Map<String, Object> trf = new HashMap<>();
        trf.put("recipientPhone", bobPhone);
        trf.put("amount", 5000);
        trf.put("pin", "1234");
        trf.put("idempotencyKey", TestData.uniqueKey("rev-trf"));
        ResponseEntity<String> trfRes = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, trf), String.class);
        String trfRef = objectMapper.readTree(trfRes.getBody()).path("data").path("reference").asText();

        // Reverse it (Alice initiates from her own session)
        ResponseEntity<String> rev = restTemplate.exchange(
                "/api/wallet/transactions/" + trfRef + "/reverse?reason=test",
                HttpMethod.POST, authedHeaders(alice), String.class);

        assertThat(rev.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Alice gets refunded 5000 + 50 fee → back to 50000
        assertThat(readBalance(alice)).isEqualByComparingTo(new BigDecimal("50000.00"));

        String bob = login("rev-trf-b");
        // Bob is debited 5000 → back to 0
        assertThat(readBalance(bob)).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void shouldRejectReversalOfAlreadyReversedTransaction() throws Exception {
        String alice = registerAndLogin("rev-twice");
        String depositRef = deposit(alice, 5000);

        ResponseEntity<String> first = restTemplate.exchange(
                "/api/wallet/transactions/" + depositRef + "/reverse?reason=first",
                HttpMethod.POST, authedHeaders(alice), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/wallet/transactions/" + depositRef + "/reverse?reason=second",
                HttpMethod.POST, authedHeaders(alice), String.class);

        // IllegalStateException("Transaction already reversed") → CONFLICT (409)
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void shouldRejectReversalOfNonCompletedTransaction() throws Exception {
        String alice = registerAndLogin("rev-failed");
        // Mint a deposit then mutate its status directly to FAILED to simulate
        // a non-completed transaction. This is the cleanest way to assert the
        // service rejects reversal on anything other than COMPLETED without
        // having to drive an actual failure flow through the API.
        String ref = deposit(alice, 1000);
        jdbcTemplate.update(
                "UPDATE transactions SET status = 'FAILED' WHERE reference = ?",
                ref);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transactions/" + ref + "/reverse?reason=test",
                HttpMethod.POST, authedHeaders(alice), String.class);

        // IllegalStateException("Only completed transactions can be reversed.") → 409
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Characterises a gap in the current implementation: reversals do NOT
     * write to {@code audit_logs}. Only admin-side actions
     * (reconcile, freeze / unfreeze, unlock-PIN) emit audit rows today. The
     * test pins zero audit rows so a future change that wires reversal
     * auditing in will fail this assertion and force the expectation up.
     */
    @Test
    void issueCharacterisation_reversalDoesNotWriteAuditLogToday() throws Exception {
        String alice = registerAndLogin("rev-audit");
        String depositRef = deposit(alice, 2000);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transactions/" + depositRef + "/reverse?reason=audit-trail-test",
                HttpMethod.POST, authedHeaders(alice), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Long auditRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs", Long.class);
        assertThat(auditRows).isEqualTo(0L);
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

    private String deposit(String token, int amount) throws Exception {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/deposit", HttpMethod.POST,
                authedJsonEntity(token, Map.of(
                        "amount", amount,
                        "idempotencyKey", TestData.uniqueKey("setup-dep"))),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(res.getBody()).path("data").path("reference").asText();
    }

    private BigDecimal readBalance(String token) throws Exception {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, authedHeaders(token), String.class);
        JsonNode data = objectMapper.readTree(res.getBody()).path("data");
        return new BigDecimal(data.path("balance").asText());
    }
}
