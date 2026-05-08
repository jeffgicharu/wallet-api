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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authorisation and cross-tenant isolation tests.
 *
 * <p>The first three tests <em>characterise the bug filed as
 * <a href="https://github.com/jeffgicharu/wallet-api/issues/2">issue #2</a></em>:
 * the {@code AdminController} is annotated only with {@code .authenticated()}
 * in the security filter chain, with no role check. Every authenticated user —
 * not just administrators — can hit every admin endpoint. These tests pin the
 * <strong>current behaviour</strong> so that, when the role check ships, they
 * fail loudly and force the developer to flip the expected status from 200 to
 * 403, which is the correct behaviour. The Javadoc on each test points at the
 * issue so the link survives review history.
 */
class AuthorizationIntegrationTest extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;

    /**
     * Documents the current (buggy) behaviour from issue #2 — a regular
     * authenticated user can read /api/admin/users/search. When issue #2 lands,
     * this assertion flips to FORBIDDEN.
     */
    @Test
    void issue2_regularUserCanCallAdminUsersSearch_currentlyAllowed() throws Exception {
        String token = registerAndLogin("regular-1", TestData.uniquePhone());
        String otherPhone = TestData.uniquePhone();
        register("admin-target", otherPhone);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/admin/users/search?phone=" + otherPhone,
                HttpMethod.GET, authedHeaders(token), String.class);

        // CURRENT behaviour: any authenticated user can hit admin endpoints.
        // Once issue #2 is fixed, this will become HttpStatus.FORBIDDEN.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Documents the current (buggy) behaviour from issue #2 — a regular
     * authenticated user can freeze any wallet. When issue #2 lands, this
     * assertion flips to FORBIDDEN.
     */
    @Test
    void issue2_regularUserCanFreezeAnyWallet_currentlyAllowed() throws Exception {
        String attacker = registerAndLogin("attacker", TestData.uniquePhone());
        String victimPhone = TestData.uniquePhone();
        register("victim", victimPhone);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/admin/wallets/" + victimPhone + "/freeze",
                HttpMethod.POST, authedHeaders(attacker), String.class);

        // CURRENT behaviour. After the issue #2 fix this becomes FORBIDDEN.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Documents the current (buggy) behaviour from issue #2 — the audit trail
     * is readable by any authenticated user. When issue #2 lands, this
     * assertion flips to FORBIDDEN.
     */
    @Test
    void issue2_regularUserCanReadAuditTrail_currentlyAllowed() throws Exception {
        String token = registerAndLogin("snoop", TestData.uniquePhone());

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/admin/audit",
                HttpMethod.GET, authedHeaders(token), String.class);

        // CURRENT behaviour. After the issue #2 fix this becomes FORBIDDEN.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Confirms the part that already works correctly — unauthenticated callers
     * never reach admin endpoints. Issue #2 does not affect this scenario.
     */
    @Test
    void shouldRejectUnauthenticatedAccessToAdminEndpoints() {
        ResponseEntity<String> users = restTemplate.exchange(
                "/api/admin/users/search?phone=+254700000000",
                HttpMethod.GET, null, String.class);
        ResponseEntity<String> stats = restTemplate.exchange(
                "/api/admin/stats", HttpMethod.GET, null, String.class);
        ResponseEntity<String> audit = restTemplate.exchange(
                "/api/admin/audit", HttpMethod.GET, null, String.class);

        // Spring Security's default response when no AuthenticationEntryPoint
        // is configured for missing-token is 403 Forbidden (not 401). The
        // important guarantee is that the controller is never reached.
        assertThat(users.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(stats.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(audit.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldIsolateBobsTransactionsFromAlicesView() throws Exception {
        String alice = registerAndLogin("iso-alice", TestData.uniquePhone());
        String bob = registerAndLogin("iso-bob", TestData.uniquePhone());

        deposit(alice, 1000);
        deposit(alice, 2000);
        deposit(bob, 5000);

        ResponseEntity<String> aliceTxns = restTemplate.exchange(
                "/api/wallet/transactions",
                HttpMethod.GET, authedHeaders(alice), String.class);
        ResponseEntity<String> bobTxns = restTemplate.exchange(
                "/api/wallet/transactions",
                HttpMethod.GET, authedHeaders(bob), String.class);

        assertThat(aliceTxns.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobTxns.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode aliceList = objectMapper.readTree(aliceTxns.getBody())
                .path("data").path("content");
        JsonNode bobList = objectMapper.readTree(bobTxns.getBody())
                .path("data").path("content");

        // Alice has 2 deposits; Bob has 1. Neither sees the other's transactions.
        assertThat(aliceList.size()).isEqualTo(2);
        assertThat(bobList.size()).isEqualTo(1);
    }

    @Test
    void shouldShowEachUserTheirOwnWalletBalanceOnly() throws Exception {
        String alice = registerAndLogin("bal-alice", TestData.uniquePhone());
        String bob = registerAndLogin("bal-bob", TestData.uniquePhone());

        deposit(alice, 1000);
        deposit(bob, 9999);

        ResponseEntity<String> aliceWallet = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, authedHeaders(alice), String.class);
        ResponseEntity<String> bobWallet = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, authedHeaders(bob), String.class);

        assertThat(objectMapper.readTree(aliceWallet.getBody())
                .path("data").path("balance").asDouble()).isEqualTo(1000.0);
        assertThat(objectMapper.readTree(bobWallet.getBody())
                .path("data").path("balance").asDouble()).isEqualTo(9999.0);
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

    private void register(String prefix, String phone) throws Exception {
        ResponseEntity<String> reg = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(TestData.uniqueEmail(prefix), phone)),
                String.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void deposit(String token, int amount) {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/deposit", HttpMethod.POST,
                authedJsonEntity(token, Map.of(
                        "amount", amount,
                        "idempotencyKey", TestData.uniqueKey("authz-dep"))),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
