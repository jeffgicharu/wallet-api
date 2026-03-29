package com.wallet.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WalletIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("Full auth flow: register then login returns JWT")
    void authFlow_registerAndLogin() throws Exception {
        register("auth-flow@test.com", "+254711000001");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"auth-flow@test.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists());
    }

    @Test
    @DisplayName("Reject duplicate registration")
    void register_duplicate_rejects() throws Exception {
        register("dup@test.com", "+254711000002");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(regBody("dup@test.com", "+254711000099")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Deposit through HTTP with valid JWT updates balance")
    void deposit_throughHttp_updatesBalance() throws Exception {
        String token = registerAndGetToken("dep-http@test.com", "+254711000003");

        mockMvc.perform(post("/api/wallet/deposit")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":25000,\"idempotencyKey\":\"http-dep-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(get("/api/wallet")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(25000.0));
    }

    @Test
    @DisplayName("Transfer through HTTP deducts from sender and credits receiver")
    void transfer_throughHttp() throws Exception {
        String aliceToken = registerAndGetToken("alice-http@test.com", "+254711000004");
        registerAndGetToken("bob-http@test.com", "+254711000005");

        deposit(aliceToken, 50000, "pre-trf-001");

        mockMvc.perform(post("/api/wallet/transfer")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientPhone\":\"+254711000005\",\"amount\":10000,\"pin\":\"1234\",\"idempotencyKey\":\"trf-http-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("TRANSFER"));
    }

    @Test
    @DisplayName("Unauthenticated request returns 403")
    void wallet_noToken_returns403() throws Exception {
        mockMvc.perform(get("/api/wallet"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Transaction lookup by reference returns correct transaction")
    void transactionLookup_returnsTransaction() throws Exception {
        String token = registerAndGetToken("lookup@test.com", "+254711000006");

        MvcResult depResult = mockMvc.perform(post("/api/wallet/deposit")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":5000,\"idempotencyKey\":\"lookup-dep\"}"))
                .andReturn();

        JsonNode data = objectMapper.readTree(depResult.getResponse().getContentAsString()).get("data");
        String reference = data.get("reference").asText();

        mockMvc.perform(get("/api/wallet/transactions/" + reference)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reference").value(reference));
    }

    @Test
    @DisplayName("Reversal reverses a completed deposit")
    void reversal_completedDeposit() throws Exception {
        String token = registerAndGetToken("rev@test.com", "+254711000007");
        MvcResult dep = deposit(token, 8000, "rev-dep-001");
        JsonNode data = objectMapper.readTree(dep.getResponse().getContentAsString()).get("data");
        String ref = data.get("reference").asText();

        mockMvc.perform(post("/api/wallet/transactions/" + ref + "/reverse")
                .header("Authorization", "Bearer " + token)
                .param("reason", "Customer request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Transaction reversed"));

        mockMvc.perform(get("/api/wallet")
                .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.balance").value(0.0));
    }

    @Test
    @DisplayName("Admin stats endpoint returns platform data")
    void adminStats_returnsData() throws Exception {
        String token = registerAndGetToken("admin-stats@test.com", "+254711000008");

        mockMvc.perform(get("/api/admin/stats")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalUsers").exists())
                .andExpect(jsonPath("$.data.totalTransactions").exists());
    }

    @Test
    @DisplayName("Admin freeze prevents transactions")
    void adminFreeze_blocksTransactions() throws Exception {
        String token = registerAndGetToken("freeze-user@test.com", "+254711000009");
        deposit(token, 10000, "freeze-dep");

        // Freeze via admin
        mockMvc.perform(post("/api/admin/wallets/+254711000009/freeze")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Attempt deposit on frozen wallet
        mockMvc.perform(post("/api/wallet/deposit")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"idempotencyKey\":\"frozen-dep\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Per-wallet reconciliation matches balance to ledger")
    void walletReconciliation_matches() throws Exception {
        String token = registerAndGetToken("recon@test.com", "+254711000010");
        deposit(token, 15000, "recon-dep");

        mockMvc.perform(get("/api/admin/reconcile/wallet/+254711000010")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matches").value(true));
    }

    @Test
    @DisplayName("Wrong PIN returns error and doesn't process transaction")
    void wrongPin_throughHttp_returnsError() throws Exception {
        String token = registerAndGetToken("badpin@test.com", "+254711000011");
        deposit(token, 5000, "badpin-dep");

        mockMvc.perform(post("/api/wallet/withdraw")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"pin\":\"9999\",\"idempotencyKey\":\"badpin-wdr\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Wallet returns daily limit usage")
    void wallet_returnsDailyLimitInfo() throws Exception {
        String token = registerAndGetToken("limits@test.com", "+254711000012");

        mockMvc.perform(get("/api/wallet")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyTransferLimit").exists())
                .andExpect(jsonPath("$.data.dailyTransferUsed").exists());
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private void register(String email, String phone) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(regBody(email, phone)))
                .andExpect(status().isCreated());
    }

    private String registerAndGetToken(String email, String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(regBody(email, phone)))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("data").get("token").asText();
    }

    private MvcResult deposit(String token, int amount, String key) throws Exception {
        return mockMvc.perform(post("/api/wallet/deposit")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"idempotencyKey\":\"" + key + "\"}"))
                .andReturn();
    }

    private String regBody(String email, String phone) {
        return String.format(
                "{\"fullName\":\"Test User\",\"email\":\"%s\",\"phoneNumber\":\"%s\",\"password\":\"password123\",\"pin\":\"1234\"}",
                email, phone);
    }
}
