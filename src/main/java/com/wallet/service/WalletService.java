package com.wallet.service;

import com.wallet.dto.request.DepositRequest;
import com.wallet.dto.request.TransferRequest;
import com.wallet.dto.request.WithdrawRequest;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.dto.response.WalletResponse;
import com.wallet.entity.*;
import com.wallet.enums.EntryType;
import com.wallet.enums.TransactionStatus;
import com.wallet.enums.TransactionType;
import com.wallet.exception.DuplicateTransactionException;
import com.wallet.exception.InsufficientBalanceException;
import com.wallet.exception.InvalidPinException;
import com.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${wallet.transfer-fee-percent}")
    private double transferFeePercent;

    @Value("${wallet.max-transfer-amount}")
    private BigDecimal maxTransferAmount;

    @Value("${wallet.min-transfer-amount}")
    private BigDecimal minTransferAmount;

    // ─── WALLET INFO ────────────────────────────────────────────────

    public WalletResponse getWallet(String email) {
        Wallet wallet = getWalletByEmail(email);
        User user = wallet.getUser();
        return toWalletResponse(wallet, user);
    }

    // ─── DEPOSIT ────────────────────────────────────────────────────

    @Transactional
    public TransactionResponse deposit(String email, DepositRequest request) {
        checkIdempotency(request.getIdempotencyKey());

        Wallet wallet = getWalletByEmail(email);
        BigDecimal amount = request.getAmount();
        String reference = generateReference("DEP", request.getIdempotencyKey());

        BigDecimal balanceBefore = wallet.getBalance();
        wallet.setBalance(balanceBefore.add(amount));
        walletRepository.save(wallet);

        Transaction txn = Transaction.builder()
                .reference(reference)
                .type(TransactionType.DEPOSIT)
                .status(TransactionStatus.COMPLETED)
                .amount(amount)
                .receiverWallet(wallet)
                .description(request.getDescription() != null ? request.getDescription() : "Cash deposit")
                .build();
        transactionRepository.save(txn);

        createLedgerEntry(txn, wallet, EntryType.CREDIT, amount, balanceBefore, wallet.getBalance());

        return toTransactionResponse(txn);
    }

    // ─── WITHDRAW ───────────────────────────────────────────────────

    @Transactional
    public TransactionResponse withdraw(String email, WithdrawRequest request) {
        checkIdempotency(request.getIdempotencyKey());

        Wallet wallet = getWalletByEmail(email);
        User user = wallet.getUser();
        validatePin(user, request.getPin());

        BigDecimal amount = request.getAmount();
        BigDecimal balanceBefore = wallet.getBalance();

        if (balanceBefore.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(
                    String.format("Insufficient balance. Available: %s %s, Requested: %s %s",
                            wallet.getCurrency(), balanceBefore, wallet.getCurrency(), amount));
        }

        wallet.setBalance(balanceBefore.subtract(amount));
        walletRepository.save(wallet);

        String reference = generateReference("WDR", request.getIdempotencyKey());

        Transaction txn = Transaction.builder()
                .reference(reference)
                .type(TransactionType.WITHDRAWAL)
                .status(TransactionStatus.COMPLETED)
                .amount(amount)
                .senderWallet(wallet)
                .description(request.getDescription() != null ? request.getDescription() : "Cash withdrawal")
                .build();
        transactionRepository.save(txn);

        createLedgerEntry(txn, wallet, EntryType.DEBIT, amount, balanceBefore, wallet.getBalance());

        return toTransactionResponse(txn);
    }

    // ─── P2P TRANSFER ───────────────────────────────────────────────

    @Transactional
    public TransactionResponse transfer(String email, TransferRequest request) {
        checkIdempotency(request.getIdempotencyKey());

        Wallet senderWallet = getWalletByEmail(email);
        User sender = senderWallet.getUser();
        validatePin(sender, request.getPin());

        if (sender.getPhoneNumber().equals(request.getRecipientPhone())) {
            throw new IllegalArgumentException("Cannot transfer to yourself");
        }

        Wallet receiverWallet = walletRepository.findByUserPhoneNumber(request.getRecipientPhone())
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + request.getRecipientPhone()));

        BigDecimal amount = request.getAmount();

        if (amount.compareTo(minTransferAmount) < 0) {
            throw new IllegalArgumentException("Minimum transfer amount is " + minTransferAmount);
        }
        if (amount.compareTo(maxTransferAmount) > 0) {
            throw new IllegalArgumentException("Maximum transfer amount is " + maxTransferAmount);
        }

        // Daily limit check
        BigDecimal todayTotal = transactionRepository.sumTransfersSince(
                senderWallet.getId(), java.time.LocalDate.now().atStartOfDay());
        if (todayTotal.add(amount).compareTo(DAILY_TRANSFER_LIMIT) > 0) {
            BigDecimal remaining = DAILY_TRANSFER_LIMIT.subtract(todayTotal).max(BigDecimal.ZERO);
            throw new IllegalArgumentException(String.format(
                    "Daily transfer limit exceeded. Limit: %s, Used today: %s, Remaining: %s",
                    DAILY_TRANSFER_LIMIT, todayTotal, remaining));
        }

        // Calculate fee
        BigDecimal fee = amount.multiply(BigDecimal.valueOf(transferFeePercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal totalDebit = amount.add(fee);

        BigDecimal senderBalanceBefore = senderWallet.getBalance();
        if (senderBalanceBefore.compareTo(totalDebit) < 0) {
            throw new InsufficientBalanceException(
                    String.format("Insufficient balance. Available: %s %s, Required: %s %s (amount: %s + fee: %s)",
                            senderWallet.getCurrency(), senderBalanceBefore,
                            senderWallet.getCurrency(), totalDebit, amount, fee));
        }

        // Debit sender
        senderWallet.setBalance(senderBalanceBefore.subtract(totalDebit));
        walletRepository.save(senderWallet);

        // Credit receiver
        BigDecimal receiverBalanceBefore = receiverWallet.getBalance();
        receiverWallet.setBalance(receiverBalanceBefore.add(amount));
        walletRepository.save(receiverWallet);

        String reference = generateReference("TRF", request.getIdempotencyKey());
        String description = request.getDescription() != null ? request.getDescription()
                : "Transfer to " + request.getRecipientPhone();

        // Main transfer transaction
        Transaction txn = Transaction.builder()
                .reference(reference)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .amount(amount)
                .fee(fee)
                .senderWallet(senderWallet)
                .receiverWallet(receiverWallet)
                .description(description)
                .build();
        transactionRepository.save(txn);

        // Double-entry ledger: sender debit, receiver credit
        createLedgerEntry(txn, senderWallet, EntryType.DEBIT, totalDebit,
                senderBalanceBefore, senderWallet.getBalance());
        createLedgerEntry(txn, receiverWallet, EntryType.CREDIT, amount,
                receiverBalanceBefore, receiverWallet.getBalance());

        // Fee transaction (separate ledger trail)
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            Transaction feeTxn = Transaction.builder()
                    .reference(reference + "-FEE")
                    .type(TransactionType.FEE)
                    .status(TransactionStatus.COMPLETED)
                    .amount(fee)
                    .senderWallet(senderWallet)
                    .description("Transfer fee for " + reference)
                    .build();
            transactionRepository.save(feeTxn);
        }

        return toTransactionResponse(txn);
    }

    // ─── TRANSACTION HISTORY ────────────────────────────────────────

    public Page<TransactionResponse> getTransactions(String email, Pageable pageable) {
        Wallet wallet = getWalletByEmail(email);
        return transactionRepository.findByWalletId(wallet.getId(), pageable)
                .map(this::toTransactionResponse);
    }

    public Page<TransactionResponse> getTransactionsByType(String email,
            com.wallet.enums.TransactionType type, Pageable pageable) {
        Wallet wallet = getWalletByEmail(email);
        return transactionRepository.findByWalletIdAndType(wallet.getId(), type, pageable)
                .map(this::toTransactionResponse);
    }

    public Page<TransactionResponse> getTransactionsByDateRange(String email,
            java.time.LocalDateTime from, java.time.LocalDateTime to, Pageable pageable) {
        Wallet wallet = getWalletByEmail(email);
        return transactionRepository.findByWalletIdAndDateRange(wallet.getId(), from, to, pageable)
                .map(this::toTransactionResponse);
    }

    // ─── REVERSAL ───────────────────────────────────────────────────

    @Transactional
    public TransactionResponse reverseTransaction(String reference, String reason) {
        Transaction original = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + reference));

        if (original.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalStateException("Transaction already reversed");
        }
        if (original.getStatus() != TransactionStatus.COMPLETED) {
            throw new IllegalStateException("Only completed transactions can be reversed. Current: " + original.getStatus());
        }
        if (original.getType() == TransactionType.FEE) {
            throw new IllegalStateException("Fee transactions cannot be reversed directly");
        }

        BigDecimal amount = original.getAmount();
        String reversalRef = "REV-" + reference;

        if (original.getType() == TransactionType.TRANSFER) {
            Wallet sender = original.getSenderWallet();
            Wallet receiver = original.getReceiverWallet();
            BigDecimal fee = original.getFee() != null ? original.getFee() : BigDecimal.ZERO;

            BigDecimal senderBefore = sender.getBalance();
            sender.setBalance(senderBefore.add(amount).add(fee));
            walletRepository.save(sender);

            BigDecimal receiverBefore = receiver.getBalance();
            if (receiverBefore.compareTo(amount) < 0) {
                throw new InsufficientBalanceException("Receiver has insufficient balance for reversal");
            }
            receiver.setBalance(receiverBefore.subtract(amount));
            walletRepository.save(receiver);

            createLedgerEntry(original, sender, EntryType.CREDIT, amount.add(fee), senderBefore, sender.getBalance());
            createLedgerEntry(original, receiver, EntryType.DEBIT, amount, receiverBefore, receiver.getBalance());

        } else if (original.getType() == TransactionType.DEPOSIT) {
            Wallet wallet = original.getReceiverWallet();
            BigDecimal before = wallet.getBalance();
            if (before.compareTo(amount) < 0) {
                throw new InsufficientBalanceException("Insufficient balance for deposit reversal");
            }
            wallet.setBalance(before.subtract(amount));
            walletRepository.save(wallet);
            createLedgerEntry(original, wallet, EntryType.DEBIT, amount, before, wallet.getBalance());

        } else if (original.getType() == TransactionType.WITHDRAWAL) {
            Wallet wallet = original.getSenderWallet();
            BigDecimal before = wallet.getBalance();
            wallet.setBalance(before.add(amount));
            walletRepository.save(wallet);
            createLedgerEntry(original, wallet, EntryType.CREDIT, amount, before, wallet.getBalance());
        }

        original.setStatus(TransactionStatus.REVERSED);
        transactionRepository.save(original);

        Transaction reversal = Transaction.builder()
                .reference(reversalRef)
                .type(original.getType())
                .status(TransactionStatus.COMPLETED)
                .amount(amount)
                .senderWallet(original.getReceiverWallet())
                .receiverWallet(original.getSenderWallet())
                .description("Reversal of " + reference + ": " + (reason != null ? reason : "No reason"))
                .build();
        transactionRepository.save(reversal);

        return toTransactionResponse(reversal);
    }

    public TransactionResponse getTransactionByReference(String reference) {
        Transaction txn = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + reference));
        return toTransactionResponse(txn);
    }

    // ─── LEDGER / STATEMENT ─────────────────────────────────────────

    public Page<LedgerEntry> getStatement(String email, Pageable pageable) {
        Wallet wallet = getWalletByEmail(email);
        return ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable);
    }

    // ─── HELPERS ────────────────────────────────────────────────────

    private Wallet getWalletByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
    }

    private static final int MAX_PIN_ATTEMPTS = 3;
    private static final int LOCKOUT_MINUTES = 15;

    private void validatePin(User user, String rawPin) {
        if (user.isPinLocked()) {
            throw new IllegalStateException("Account is locked due to too many failed PIN attempts. Try again after "
                    + user.getPinLockedUntil().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        }

        if (!passwordEncoder.matches(rawPin, user.getPin())) {
            user.setFailedPinAttempts(user.getFailedPinAttempts() + 1);
            if (user.getFailedPinAttempts() >= MAX_PIN_ATTEMPTS) {
                user.setPinLockedUntil(java.time.LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
            }
            userRepository.save(user);
            int remaining = MAX_PIN_ATTEMPTS - user.getFailedPinAttempts();
            if (remaining > 0) {
                throw new InvalidPinException();
            } else {
                throw new IllegalStateException("Account locked for " + LOCKOUT_MINUTES
                        + " minutes after " + MAX_PIN_ATTEMPTS + " failed PIN attempts");
            }
        }

        if (user.getFailedPinAttempts() > 0) {
            user.setFailedPinAttempts(0);
            user.setPinLockedUntil(null);
            userRepository.save(user);
        }
    }

    private void checkIdempotency(String idempotencyKey) {
        if (transactionRepository.existsByReference("DEP-" + idempotencyKey)
                || transactionRepository.existsByReference("WDR-" + idempotencyKey)
                || transactionRepository.existsByReference("TRF-" + idempotencyKey)) {
            throw new DuplicateTransactionException(
                    "Transaction with this idempotency key has already been processed");
        }
    }

    private String generateReference(String prefix, String idempotencyKey) {
        return prefix + "-" + idempotencyKey;
    }

    private void createLedgerEntry(Transaction txn, Wallet wallet, EntryType type,
                                   BigDecimal amount, BigDecimal before, BigDecimal after) {
        LedgerEntry entry = LedgerEntry.builder()
                .transaction(txn)
                .wallet(wallet)
                .entryType(type)
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(after)
                .build();
        ledgerEntryRepository.save(entry);
    }

    private static final BigDecimal DAILY_TRANSFER_LIMIT = new BigDecimal("300000.00");

    private WalletResponse toWalletResponse(Wallet wallet, User user) {
        return WalletResponse.builder()
                .walletId(wallet.getId())
                .ownerName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .balance(wallet.getBalance())
                .currency(wallet.getCurrency())
                .active(wallet.isActive())
                .dailyTransferLimit(DAILY_TRANSFER_LIMIT)
                .dailyTransferUsed(transactionRepository.sumTransfersSince(
                        wallet.getId(), java.time.LocalDate.now().atStartOfDay()))
                .createdAt(wallet.getCreatedAt())
                .build();
    }

    private TransactionResponse toTransactionResponse(Transaction txn) {
        return TransactionResponse.builder()
                .reference(txn.getReference())
                .type(txn.getType())
                .status(txn.getStatus())
                .amount(txn.getAmount())
                .fee(txn.getFee())
                .senderPhone(txn.getSenderWallet() != null
                        ? txn.getSenderWallet().getUser().getPhoneNumber() : null)
                .receiverPhone(txn.getReceiverWallet() != null
                        ? txn.getReceiverWallet().getUser().getPhoneNumber() : null)
                .description(txn.getDescription())
                .createdAt(txn.getCreatedAt())
                .build();
    }
}
