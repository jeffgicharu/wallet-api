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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIntegrationTest extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void shouldReportBalancedReconciliationOnFreshDatabase() throws Exception {
        // No users, no transactions, no ledger entries. Reconciliation must
        // still report balanced=true (sum debits = sum credits = 0).
        String token = registerAdminAndLogin("recon-empty", TestData.uniquePhone());

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/admin/reconcile", HttpMethod.GET, authedHeaders(token), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(res.getBody()).path("data");
        assertThat(data.path("balanced").asBoolean()).isTrue();
    }

    /**
     * Issue #11 fixed: a system_cash counterpart entry is now posted for
     * every external cash movement (deposit/withdrawal/fee) and their
     * reversals, so total debits == total credits and reconciliation
     * reports {@code balanced=true} after arbitrary activity.
     */
    @Test
    void systemReconciliationIsBalancedAfterActivity() throws Exception {
        String alice = registerAndLogin("recon-a", TestData.uniquePhone());
        String bobPhone = TestData.uniquePhone();
        register("recon-b", bobPhone);

        // Build up activity: 2 deposits, 1 transfer, 1 reversal of one deposit
        depositAndReturnRef(alice, 10000);
        String secondDepositRef = depositAndReturnRef(alice, 20000);

        Map<String, Object> trf = new HashMap<>();
        trf.put("recipientPhone", bobPhone);
        trf.put("amount", 3000);
        trf.put("pin", "1234");
        trf.put("idempotencyKey", TestData.uniqueKey("recon-trf"));
        ResponseEntity<String> trfRes = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(alice, trf), String.class);
        assertThat(trfRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> revRes = restTemplate.exchange(
                "/api/wallet/transactions/" + secondDepositRef + "/reverse?reason=test",
                HttpMethod.POST, authedHeaders(alice), String.class);
        assertThat(revRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        String adminToken = registerAdminAndLogin("recon-admin", TestData.uniquePhone());
        ResponseEntity<String> recon = restTemplate.exchange(
                "/api/admin/reconcile", HttpMethod.GET, authedHeaders(adminToken), String.class);
        assertThat(recon.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(recon.getBody()).path("data");
        // Every cash movement now has a system_cash counterpart, so the
        // books balance after deposits + transfer (fee) + reversal.
        assertThat(data.path("balanced").asBoolean()).isTrue();
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private String registerAndLogin(String prefix, String phone) throws Exception {
        String email = TestData.uniqueEmail(prefix);
        ResponseEntity<String> reg = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(email, phone)),
                String.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(reg.getBody()).path("data").path("token").asText();
    }

    /**
     * /api/admin/reconcile is ADMIN-only (issue #2). Register, promote in
     * the DB, return the token (authorities are loaded per request).
     */
    private String registerAdminAndLogin(String prefix, String phone) throws Exception {
        String email = TestData.uniqueEmail(prefix);
        ResponseEntity<String> reg = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(email, phone)),
                String.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        return objectMapper.readTree(reg.getBody()).path("data").path("token").asText();
    }

    private void register(String prefix, String phone) throws Exception {
        ResponseEntity<String> reg = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(TestData.uniqueEmail(prefix), phone)),
                String.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String depositAndReturnRef(String token, int amount) throws Exception {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/deposit", HttpMethod.POST,
                authedJsonEntity(token, Map.of(
                        "amount", amount,
                        "idempotencyKey", TestData.uniqueKey("recon-dep"))),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(res.getBody()).path("data").path("reference").asText();
    }
}
