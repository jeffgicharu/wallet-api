package com.wallet.controller;

import com.wallet.dto.response.ApiResponse;
import com.wallet.dto.response.WalletResponse;
import com.wallet.entity.AuditLog;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.UserRepository;
import com.wallet.repository.WalletRepository;
import com.wallet.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Administrative operations for wallet management")
public class AdminController {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService auditService;

    @GetMapping("/users/search")
    @Operation(summary = "Search for a user by phone or email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchUser(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email) {

        User user = null;
        if (phone != null) {
            user = userRepository.findByPhoneNumber(phone).orElse(null);
        } else if (email != null) {
            user = userRepository.findByEmail(email).orElse(null);
        }

        if (user == null) {
            return ResponseEntity.ok(ApiResponse.error("User not found"));
        }

        Wallet wallet = walletRepository.findByUserId(user.getId()).orElse(null);
        String searchBy = phone != null ? phone : email;
        auditService.log("USER_LOOKUP", "USER", searchBy, "admin", "Searched by " + (phone != null ? "phone" : "email"));

        return ResponseEntity.ok(ApiResponse.success("User found", Map.of(
                "id", user.getId(),
                "fullName", user.getFullName(),
                "email", user.getEmail(),
                "phone", user.getPhoneNumber(),
                "walletActive", wallet != null && wallet.isActive(),
                "balance", wallet != null ? wallet.getBalance() : BigDecimal.ZERO,
                "failedPinAttempts", user.getFailedPinAttempts(),
                "pinLocked", user.isPinLocked(),
                "createdAt", user.getCreatedAt()
        )));
    }

    @PostMapping("/wallets/{phone}/freeze")
    @Operation(summary = "Freeze a wallet (block all transactions)")
    public ResponseEntity<ApiResponse<String>> freezeWallet(@PathVariable String phone) {
        Wallet wallet = walletRepository.findByUserPhoneNumber(phone)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for: " + phone));
        wallet.setActive(false);
        walletRepository.save(wallet);
        auditService.log("WALLET_FROZEN", "WALLET", phone, "admin", "Wallet frozen by admin");
        return ResponseEntity.ok(ApiResponse.success("Wallet frozen", phone));
    }

    @PostMapping("/wallets/{phone}/unfreeze")
    @Operation(summary = "Unfreeze a wallet")
    public ResponseEntity<ApiResponse<String>> unfreezeWallet(@PathVariable String phone) {
        Wallet wallet = walletRepository.findByUserPhoneNumber(phone)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for: " + phone));
        wallet.setActive(true);
        walletRepository.save(wallet);
        auditService.log("WALLET_UNFROZEN", "WALLET", phone, "admin", "Wallet unfrozen by admin");
        return ResponseEntity.ok(ApiResponse.success("Wallet unfrozen", phone));
    }

    @PostMapping("/users/{phone}/unlock-pin")
    @Operation(summary = "Unlock a PIN-locked account")
    public ResponseEntity<ApiResponse<String>> unlockPin(@PathVariable String phone) {
        User user = userRepository.findByPhoneNumber(phone)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + phone));
        user.setFailedPinAttempts(0);
        user.setPinLockedUntil(null);
        userRepository.save(user);
        auditService.log("PIN_UNLOCKED", "USER", phone, "admin", "PIN lock cleared by admin");
        return ResponseEntity.ok(ApiResponse.success("PIN unlocked", phone));
    }

    @GetMapping("/stats")
    @Operation(summary = "Platform-wide statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> platformStats() {
        long totalUsers = userRepository.count();
        long totalWallets = walletRepository.count();
        long totalTransactions = transactionRepository.count();
        BigDecimal totalVolume = transactionRepository.sumAllCompleted();

        return ResponseEntity.ok(ApiResponse.success("Platform stats", Map.of(
                "totalUsers", totalUsers,
                "totalWallets", totalWallets,
                "totalTransactions", totalTransactions,
                "totalVolume", totalVolume
        )));
    }

    @GetMapping("/audit")
    @Operation(summary = "View audit trail")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> getAuditLog(
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Audit log", auditService.getAll(pageable)));
    }

    @GetMapping("/audit/{targetType}/{targetId}")
    @Operation(summary = "View audit trail for a specific entity")
    public ResponseEntity<ApiResponse<List<AuditLog>>> getAuditForTarget(
            @PathVariable String targetType,
            @PathVariable String targetId) {
        return ResponseEntity.ok(ApiResponse.success("Audit log",
                auditService.getByTarget(targetType, targetId)));
    }

    @GetMapping("/reconcile")
    @Operation(summary = "System-wide reconciliation", description = "Verify that total debits equal total credits across the entire ledger")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconcile() {
        BigDecimal totalDebits = ledgerEntryRepository.sumAllDebits();
        BigDecimal totalCredits = ledgerEntryRepository.sumAllCredits();
        BigDecimal difference = totalDebits.subtract(totalCredits).abs();
        boolean balanced = difference.compareTo(new BigDecimal("0.01")) < 0;

        auditService.log("RECONCILIATION", "SYSTEM", "GLOBAL", "admin",
                "Balanced: " + balanced + ", difference: " + difference);

        return ResponseEntity.ok(ApiResponse.success(balanced ? "Books are balanced" : "DISCREPANCY DETECTED",
                Map.of(
                        "totalDebits", totalDebits,
                        "totalCredits", totalCredits,
                        "difference", difference,
                        "balanced", balanced
                )));
    }

    @GetMapping("/reconcile/wallet/{phone}")
    @Operation(summary = "Per-wallet reconciliation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconcileWallet(@PathVariable String phone) {
        Wallet wallet = walletRepository.findByUserPhoneNumber(phone)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + phone));
        BigDecimal debits = ledgerEntryRepository.sumDebitsByWallet(wallet.getId());
        BigDecimal credits = ledgerEntryRepository.sumCreditsByWallet(wallet.getId());
        BigDecimal netBalance = credits.subtract(debits);
        boolean matches = netBalance.compareTo(wallet.getBalance()) == 0;

        return ResponseEntity.ok(ApiResponse.success(
                matches ? "Wallet balance matches ledger" : "BALANCE MISMATCH",
                Map.of(
                        "phone", phone,
                        "walletBalance", wallet.getBalance(),
                        "ledgerNetBalance", netBalance,
                        "totalDebits", debits,
                        "totalCredits", credits,
                        "matches", matches
                )));
    }
}
