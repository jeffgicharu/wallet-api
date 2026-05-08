package com.wallet.integration.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * Base class for every integration test. Boots a real Spring context against
 * a Testcontainers Postgres 16 instance.
 *
 * <p>The container is a JVM-wide singleton with {@code withReuse(true)} so that
 * a single Postgres process backs every subclass and survives between
 * <em>test runs</em> as long as the developer has set
 * {@code testcontainers.reuse.enable=true} in {@code ~/.testcontainers.properties}.
 * In CI the reuse flag is absent, so the container starts fresh once per job
 * and is reused across all integration test classes within that job.
 *
 * <p>Each test method gets a clean database via {@link #wipeDatabase()} in
 * {@link BeforeEach}, which truncates the five domain tables with cascade.
 * Tests therefore have no order dependency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("wallet_test")
                .withUsername("wallet_test")
                .withPassword("wallet_test")
                .withReuse(true);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void wipeDatabase() {
        // Order matters only for FK semantics; CASCADE on each TRUNCATE covers it.
        jdbcTemplate.execute(
                "TRUNCATE TABLE audit_logs, ledger_entries, transactions, wallets, users " +
                "RESTART IDENTITY CASCADE");
    }

    // ─── Convenience helpers shared by every subclass ─────────────────

    protected HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    protected HttpEntity<Object> authedJsonEntity(String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        return new HttpEntity<>(body, headers);
    }

    protected HttpEntity<Void> authedHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return new HttpEntity<>(headers);
    }

    protected Map<String, Object> registerBody(String email, String phone) {
        return Map.of(
                "fullName", "Test " + email,
                "email", email,
                "phoneNumber", phone,
                "password", "password123",
                "pin", "1234"
        );
    }
}
