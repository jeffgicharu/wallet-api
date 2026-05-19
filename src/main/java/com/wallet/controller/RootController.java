package com.wallet.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public service index at the API root. Hitting the bare origin used to
 * return an empty 403; this gives a recruiter or integrator a clean,
 * self-describing JSON entry point that links to the docs and health
 * endpoints.
 */
@RestController
@Tag(name = "Service", description = "Service metadata")
public class RootController {

    @GetMapping("/")
    @Operation(summary = "Service index",
            description = "Name, version, and links to the API documentation and health endpoints")
    public ResponseEntity<Map<String, Object>> index() {
        return ResponseEntity.ok(Map.of(
                "name", "Wallet API",
                "description", "M-Pesa-style mobile money wallet — deposit, withdraw, "
                        + "P2P transfer with a double-entry ledger",
                "version", "1.0.0",
                "status", "ok",
                "documentation", "/swagger-ui.html",
                "openapi", "/api-docs",
                "health", "/actuator/health"
        ));
    }
}
