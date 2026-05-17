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
import com.wallet.exception.InsufficientBalanceException;
import com.wallet.exception.InvalidPinException;
import com.wallet.exception.ResourceNotFoundException;
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
    private final PinAttemptService pinAttemptService;
    private final AuditService auditService;

    @Value("${wallet.default-currency}")
    private String defaultCurrency;

    @Value("${wallet.transfer-fee-percent}")
    private double transferFeePercent;

    @Value("${wallet.max-transfer-amount}")
    private BigDecimal maxTransferAmount;

    @Value("${wallet.min-transfer-amount}")
    private BigDecimal minTransferAmount;

    // ─── WALLET INFO ────────────────────────────────────────────────

    // readOnly tx so the lazy wallet.getUser() association resolves
    // inside a session now that open-in-view is disabled (issue #5).
    @Transactional(readOnly = true)
    public WalletResponse getWallet(String email) {
        Wallet wallet = getWalletByEmail(email);
        User user = wallet.getUser();
        return toWalletResponse(wallet, user);
    }

    // ─── DEPOSIT ────────────────────────────────────────────────────

    @RetryOnLockConflict
    @Transactional
    public TransactionResponse deposit(String email, DepositRequest request) {
        var replay = findOriginal("DEP", request.getIdempotencyKey());
        if (replay.isPresent()) return replay.get();

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
        // Counterpart: cash entered the system from outside (issue #11).
        postSystemCounterpart(txn, EntryType.DEBIT, amount);

        return toTransactionResponse(txn);
    }

    // ─── WITHDRAW ───────────────────────────────────────────────────

    @RetryOnLockConflict
    @Transactional
    public TransactionResponse withdraw(String email, WithdrawRequest request) {
        var replay = findOriginal("WDR", request.getIdempotencyKey());
        if (replay.isPresent()) return replay.get();

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
        // Counterpart: cash left the system (issue #11).
        postSystemCounterpart(txn, EntryType.CREDIT, amount);

        return toTransactionResponse(txn);
    }

    // ─── P2P TRANSFER ───────────────────────────────────────────────

    @RetryOnLockConflict
    @Transactional
    public TransactionResponse transfer(String email, TransferRequest request) {
        var replay = findOriginal("TRF", request.getIdempotencyKey());
        if (replay.isPresent()) return replay.get();

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
        // Sender is debited amount+fee but receiver is only credited
        // amount; the fee leaves the system, so credit system_cash the
        // fee to keep debits == credits (issue #11).
        postSystemCounterpart(txn, EntryType.CREDIT, fee);

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

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(String email, Pageable pageable) {
        Wallet wallet = getWalletByEmail(email);
        return transactionRepository.findByWalletId(wallet.getId(), pageable)
                .map(this::toTransactionResponse);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactionsByType(String email,
            com.wallet.enums.TransactionType type, Pageable pageable) {
        Wallet wallet = getWalletByEmail(email);
        return transactionRepository.findByWalletIdAndType(wallet.getId(), type, pageable)
                .map(this::toTransactionResponse);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactionsByDateRange(String email,
            java.time.LocalDateTime from, java.time.LocalDateTime to, Pageable pageable) {
        Wallet wallet = getWalletByEmail(email);
        return transactionRepository.findByWalletIdAndDateRange(wallet.getId(), from, to, pageable)
                .map(this::toTransactionResponse);
    }

    // ─── REVERSAL ───────────────────────────────────────────────────

    @RetryOnLockConflict
    @Transactional
    public TransactionResponse reverseTransaction(String actor, String reference, String reason) {
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
            // Mirror of the original fee counterpart (issue #11).
            postSystemCounterpart(original, EntryType.DEBIT, fee);

        } else if (original.getType() == TransactionType.DEPOSIT) {
            Wallet wallet = original.getReceiverWallet();
            BigDecimal before = wallet.getBalance();
            if (before.compareTo(amount) < 0) {
                throw new InsufficientBalanceException("Insufficient balance for deposit reversal");
            }
            wallet.setBalance(before.subtract(amount));
            walletRepository.save(wallet);
            createLedgerEntry(original, wallet, EntryType.DEBIT, amount, before, wallet.getBalance());
            postSystemCounterpart(original, EntryType.CREDIT, amount);

        } else if (original.getType() == TransactionType.WITHDRAWAL) {
            Wallet wallet = original.getSenderWallet();
            BigDecimal before = wallet.getBalance();
            wallet.setBalance(before.add(amount));
            walletRepository.save(wallet);
            createLedgerEntry(original, wallet, EntryType.CREDIT, amount, before, wallet.getBalance());
            postSystemCounterpart(original, EntryType.DEBIT, amount);
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

        // Issue #12: record the reversal in the audit log so admins can
        // see who reversed what, when, and why.
        auditService.log(
                "REVERSAL",
                "TRANSACTION",
                reference,
                actor,
                "Reversed " + reference + " -> " + reversalRef
                        + "; reason: " + (reason != null ? reason : "No reason"));

        return toTransactionResponse(reversal);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransactionByReference(String email, String reference) {
        Transaction txn = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + reference));

        // Issue #20: the caller may only see a transaction their own
        // wallet sent or received. Anything else returns the SAME 404 as
        // a genuinely-missing reference so existence isn't leaked.
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Long walletId = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"))
                .getId();

        Long senderId = txn.getSenderWallet() != null ? txn.getSenderWallet().getId() : null;
        Long receiverId = txn.getReceiverWallet() != null ? txn.getReceiverWallet().getId() : null;
        if (!walletId.equals(senderId) && !walletId.equals(receiverId)) {
            throw new ResourceNotFoundException("Transaction not found: " + reference);
        }

        return toTransactionResponse(txn);
    }

    // ─── LEDGER / STATEMENT ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<LedgerEntry> getStatement(String email, Pageable pageable) {
        Wallet wallet = getWalletByEmail(email);
        return ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable);
    }

    // ─── HELPERS ────────────────────────────────────────────────────

    private Wallet getWalletByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
        if (!wallet.isActive()) {
            throw new IllegalStateException("Wallet is frozen. Contact support.");
        }
        return wallet;
    }


    private void validatePin(User user, String rawPin) {
        // Lock check against the independently-committed counter (issue
        // #9) — not this entity, which was loaded in the caller's
        // transaction and may predate a prior failure's own-tx commit.
        if (pinAttemptService.isLocked(user.getId())) {
            throw new IllegalStateException(
                    "Account is locked for " + PinAttemptService.LOCKOUT_MINUTES
                            + " minutes due to too many failed PIN attempts.");
        }

        if (!passwordEncoder.matches(rawPin, user.getPin())) {
            // Persist the failed attempt in its OWN transaction so it
            // survives this method's rollback (the caller is
            // @Transactional and the throw below rolls it back).
            PinAttemptService.FailureResult result =
                    pinAttemptService.recordFailure(user.getId());
            if (result.locked()) {
                throw new IllegalStateException("Account locked for "
                        + PinAttemptService.LOCKOUT_MINUTES + " minutes after "
                        + PinAttemptService.MAX_PIN_ATTEMPTS + " failed PIN attempts");
            }
            throw new InvalidPinException();
        }

        // Correct PIN — clear any prior failures in its own tx.
        if (user.getFailedPinAttempts() > 0 || user.getPinLockedUntil() != null) {
            pinAttemptService.reset(user.getId());
        }
    }

    /**
     * Idempotent replay (issue #10): the transaction reference is the
     * deterministic {PREFIX}-{key}. If a transaction already exists for
     * this operation's key, return its original response so a retried
     * request (e.g. the client never saw the first 2xx) gets the same
     * result instead of a 409. The check is scoped to the operation's
     * own prefix so a deposit and a transfer can reuse the same key.
     */
    private java.util.Optional<TransactionResponse> findOriginal(String prefix,
                                                                 String idempotencyKey) {
        return transactionRepository
                .findByReference(prefix + "-" + idempotencyKey)
                .map(this::toTransactionResponse);
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

    // ─── SYSTEM CASH LEDGER (issue #11) ──────────────────────────────
    //
    // Deposits/withdrawals/fees only ever touched a user wallet, so
    // system-wide debits never equalled credits and reconciliation was
    // always balanced=false. A reserved "system cash" account holds the
    // counterpart of every external cash movement: deposit credits the
    // user + debits system_cash; withdrawal debits the user + credits
    // system_cash; the transfer fee (which leaves the sender but isn't
    // credited to the receiver) credits system_cash. With the
    // counterpart entry, sum(DEBIT) == sum(CREDIT) for any activity.

    private static final String SYSTEM_CASH_EMAIL = "system-cash@internal.wallet";
    private static final String SYSTEM_CASH_PHONE = "+000000000000";

    private Wallet systemCashWallet() {
        User sys = userRepository.findByEmail(SYSTEM_CASH_EMAIL).orElseGet(() -> {
            // Not a login account. Use a random, never-disclosed,
            // BCrypt-hashed value so no credential can ever match it
            // (and so there's no hard-coded password literal).
            String unusable = passwordEncoder.encode(
                    java.util.UUID.randomUUID().toString());
            return userRepository.save(User.builder()
                    .fullName("System Cash")
                    .email(SYSTEM_CASH_EMAIL)
                    .phoneNumber(SYSTEM_CASH_PHONE)
                    .password(unusable)
                    .pin(unusable)
                    .build());
        });
        return walletRepository.findByUserId(sys.getId()).orElseGet(() ->
                walletRepository.save(Wallet.builder()
                        .user(sys)
                        .balance(BigDecimal.ZERO)
                        .currency(defaultCurrency)
                        .active(true)
                        .build()));
    }

    /**
     * Post the system-cash counterpart for a cash movement. CREDIT
     * increases the system balance, DEBIT decreases it (mirrors user
     * wallet semantics). system_cash net position is the negative of
     * the sum of user balances — expected for a cash-drawer account and
     * allowed to go negative.
     */
    private void postSystemCounterpart(Transaction txn, EntryType type, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Wallet sys = systemCashWallet();
        BigDecimal before = sys.getBalance();
        BigDecimal after = type == EntryType.CREDIT
                ? before.add(amount)
                : before.subtract(amount);
        sys.setBalance(after);
        walletRepository.save(sys);
        createLedgerEntry(txn, sys, type, amount, before, after);
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
