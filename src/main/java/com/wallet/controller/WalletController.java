package com.wallet.controller;

import com.wallet.dto.request.DepositRequest;
import com.wallet.dto.request.TransferRequest;
import com.wallet.dto.request.WithdrawRequest;
import com.wallet.dto.response.ApiResponse;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.dto.response.WalletResponse;
import com.wallet.entity.LedgerEntry;
import com.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Wallet operations — deposit, withdraw, transfer, history")
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    @Operation(summary = "Get wallet details", description = "Returns current balance and wallet info")
    public ResponseEntity<ApiResponse<WalletResponse>> getWallet(Authentication auth) {
        WalletResponse response = walletService.getWallet(auth.getName());
        return ResponseEntity.ok(ApiResponse.success("Wallet retrieved", response));
    }

    @PostMapping("/deposit")
    @Operation(summary = "Deposit money", description = "Add funds to your wallet")
    public ResponseEntity<ApiResponse<TransactionResponse>> deposit(
            Authentication auth,
            @Valid @RequestBody DepositRequest request) {
        TransactionResponse response = walletService.deposit(auth.getName(), request);
        return ResponseEntity.ok(ApiResponse.success("Deposit successful", response));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw money", description = "Withdraw funds from your wallet (requires PIN)")
    public ResponseEntity<ApiResponse<TransactionResponse>> withdraw(
            Authentication auth,
            @Valid @RequestBody WithdrawRequest request) {
        TransactionResponse response = walletService.withdraw(auth.getName(), request);
        return ResponseEntity.ok(ApiResponse.success("Withdrawal successful", response));
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer money", description = "Send money to another user by phone number (requires PIN)")
    public ResponseEntity<ApiResponse<TransactionResponse>> transfer(
            Authentication auth,
            @Valid @RequestBody TransferRequest request) {
        TransactionResponse response = walletService.transfer(auth.getName(), request);
        return ResponseEntity.ok(ApiResponse.success("Transfer successful", response));
    }

    @GetMapping("/transactions")
    @Operation(summary = "Transaction history", description = "Paginated list of all transactions, filterable by type")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getTransactions(
            Authentication auth,
            @RequestParam(required = false) com.wallet.enums.TransactionType type,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<TransactionResponse> page;
        if (type != null) {
            page = walletService.getTransactionsByType(auth.getName(), type, pageable);
        } else {
            page = walletService.getTransactions(auth.getName(), pageable);
        }
        return ResponseEntity.ok(ApiResponse.success("Transactions retrieved", page));
    }

    @GetMapping("/statement")
    @Operation(summary = "Account statement", description = "Double-entry ledger showing balance before/after each operation")
    public ResponseEntity<ApiResponse<Page<LedgerEntry>>> getStatement(
            Authentication auth,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<LedgerEntry> page = walletService.getStatement(auth.getName(), pageable);
        return ResponseEntity.ok(ApiResponse.success("Statement retrieved", page));
    }

    @PostMapping("/transactions/{reference}/reverse")
    @Operation(summary = "Reverse a transaction", description = "Reverses a completed transaction and refunds all parties")
    public ResponseEntity<ApiResponse<TransactionResponse>> reverse(
            @PathVariable String reference,
            @RequestParam(required = false) String reason) {
        TransactionResponse response = walletService.reverseTransaction(reference, reason);
        return ResponseEntity.ok(ApiResponse.success("Transaction reversed", response));
    }

    @GetMapping("/transactions/{reference}")
    @Operation(summary = "Look up transaction by reference")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(@PathVariable String reference) {
        return ResponseEntity.ok(ApiResponse.success("Transaction found",
                walletService.getTransactionByReference(reference)));
    }
}
