package com.wallet.service;

import com.wallet.dto.request.DepositRequest;
import com.wallet.dto.request.TransferRequest;
import com.wallet.dto.request.WithdrawRequest;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.dto.response.WalletResponse;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.enums.TransactionStatus;
import com.wallet.enums.TransactionType;
import com.wallet.exception.DuplicateTransactionException;
import com.wallet.exception.InsufficientBalanceException;
import com.wallet.exception.InvalidPinException;
import com.wallet.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class WalletServiceTest {

    @Autowired private WalletService walletService;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder()
                .fullName("Alice Wanjiku")
                .email("alice@test.com")
                .phoneNumber("+254700000001")
                .password(passwordEncoder.encode("password123"))
                .pin(passwordEncoder.encode("1234"))
                .build());

        walletRepository.save(Wallet.builder()
                .user(alice)
                .balance(BigDecimal.ZERO)
                .currency("KES")
                .active(true)
                .build());

        bob = userRepository.save(User.builder()
                .fullName("Bob Ochieng")
                .email("bob@test.com")
                .phoneNumber("+254700000002")
                .password(passwordEncoder.encode("password456"))
                .pin(passwordEncoder.encode("5678"))
                .build());

        walletRepository.save(Wallet.builder()
                .user(bob)
                .balance(BigDecimal.ZERO)
                .currency("KES")
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Should return wallet with zero balance for new user")
    void getWallet_newUser_returnsZeroBalance() {
        WalletResponse wallet = walletService.getWallet("alice@test.com");
        assertEquals(BigDecimal.ZERO.setScale(2), wallet.getBalance().setScale(2));
        assertEquals("KES", wallet.getCurrency());
        assertTrue(wallet.isActive());
    }

    @Test
    @DisplayName("Should deposit money and update balance")
    void deposit_validAmount_updatesBalance() {
        DepositRequest request = new DepositRequest();
        request.setAmount(new BigDecimal("5000.00"));
        request.setIdempotencyKey("dep-001");

        TransactionResponse response = walletService.deposit("alice@test.com", request);

        assertEquals(TransactionType.DEPOSIT, response.getType());
        assertEquals(TransactionStatus.COMPLETED, response.getStatus());
        assertEquals(new BigDecimal("5000.00"), response.getAmount());

        WalletResponse wallet = walletService.getWallet("alice@test.com");
        assertEquals(0, new BigDecimal("5000.00").compareTo(wallet.getBalance()));
    }

    @Test
    @DisplayName("Should reject duplicate deposit with same idempotency key")
    void deposit_duplicateKey_throwsException() {
        DepositRequest request = new DepositRequest();
        request.setAmount(new BigDecimal("1000.00"));
        request.setIdempotencyKey("dep-dup");

        walletService.deposit("alice@test.com", request);

        assertThrows(DuplicateTransactionException.class,
                () -> walletService.deposit("alice@test.com", request));
    }

    @Test
    @DisplayName("Should withdraw money with valid PIN")
    void withdraw_validPin_updatesBalance() {
        // Deposit first
        DepositRequest deposit = new DepositRequest();
        deposit.setAmount(new BigDecimal("10000.00"));
        deposit.setIdempotencyKey("dep-for-withdraw");
        walletService.deposit("alice@test.com", deposit);

        // Withdraw
        WithdrawRequest withdraw = new WithdrawRequest();
        withdraw.setAmount(new BigDecimal("3000.00"));
        withdraw.setPin("1234");
        withdraw.setIdempotencyKey("wdr-001");

        TransactionResponse response = walletService.withdraw("alice@test.com", withdraw);

        assertEquals(TransactionType.WITHDRAWAL, response.getType());
        assertEquals(TransactionStatus.COMPLETED, response.getStatus());

        WalletResponse wallet = walletService.getWallet("alice@test.com");
        assertEquals(0, new BigDecimal("7000.00").compareTo(wallet.getBalance()));
    }

    @Test
    @DisplayName("Should reject withdrawal with wrong PIN")
    void withdraw_wrongPin_throwsException() {
        DepositRequest deposit = new DepositRequest();
        deposit.setAmount(new BigDecimal("5000.00"));
        deposit.setIdempotencyKey("dep-for-bad-pin");
        walletService.deposit("alice@test.com", deposit);

        WithdrawRequest withdraw = new WithdrawRequest();
        withdraw.setAmount(new BigDecimal("1000.00"));
        withdraw.setPin("9999");
        withdraw.setIdempotencyKey("wdr-bad-pin");

        assertThrows(InvalidPinException.class,
                () -> walletService.withdraw("alice@test.com", withdraw));
    }

    @Test
    @DisplayName("Should reject withdrawal when insufficient balance")
    void withdraw_insufficientBalance_throwsException() {
        WithdrawRequest withdraw = new WithdrawRequest();
        withdraw.setAmount(new BigDecimal("1000.00"));
        withdraw.setPin("1234");
        withdraw.setIdempotencyKey("wdr-no-funds");

        assertThrows(InsufficientBalanceException.class,
                () -> walletService.withdraw("alice@test.com", withdraw));
    }

    @Test
    @DisplayName("Should transfer money between users with fee")
    void transfer_validRequest_movesMoneyAndChargesFee() {
        // Fund Alice
        DepositRequest deposit = new DepositRequest();
        deposit.setAmount(new BigDecimal("10000.00"));
        deposit.setIdempotencyKey("dep-for-transfer");
        walletService.deposit("alice@test.com", deposit);

        // Alice sends 5000 to Bob
        TransferRequest transfer = new TransferRequest();
        transfer.setRecipientPhone("+254700000002");
        transfer.setAmount(new BigDecimal("5000.00"));
        transfer.setPin("1234");
        transfer.setIdempotencyKey("trf-001");

        TransactionResponse response = walletService.transfer("alice@test.com", transfer);

        assertEquals(TransactionType.TRANSFER, response.getType());
        assertEquals(TransactionStatus.COMPLETED, response.getStatus());
        assertEquals(new BigDecimal("5000.00"), response.getAmount());
        assertTrue(response.getFee().compareTo(BigDecimal.ZERO) > 0);

        // Verify balances: Alice = 10000 - 5000 - fee, Bob = 5000
        WalletResponse aliceWallet = walletService.getWallet("alice@test.com");
        WalletResponse bobWallet = walletService.getWallet("bob@test.com");

        assertEquals(0, new BigDecimal("5000.00").compareTo(bobWallet.getBalance()));
        // Alice should have less than 5000 due to fee
        assertTrue(aliceWallet.getBalance().compareTo(new BigDecimal("5000.00")) < 0);
    }

    @Test
    @DisplayName("Should reject transfer to self")
    void transfer_toSelf_throwsException() {
        DepositRequest deposit = new DepositRequest();
        deposit.setAmount(new BigDecimal("5000.00"));
        deposit.setIdempotencyKey("dep-self");
        walletService.deposit("alice@test.com", deposit);

        TransferRequest transfer = new TransferRequest();
        transfer.setRecipientPhone("+254700000001");
        transfer.setAmount(new BigDecimal("1000.00"));
        transfer.setPin("1234");
        transfer.setIdempotencyKey("trf-self");

        assertThrows(IllegalArgumentException.class,
                () -> walletService.transfer("alice@test.com", transfer));
    }

    @Test
    @DisplayName("Should reject transfer to non-existent recipient")
    void transfer_unknownRecipient_throwsException() {
        DepositRequest deposit = new DepositRequest();
        deposit.setAmount(new BigDecimal("5000.00"));
        deposit.setIdempotencyKey("dep-unknown");
        walletService.deposit("alice@test.com", deposit);

        TransferRequest transfer = new TransferRequest();
        transfer.setRecipientPhone("+254799999999");
        transfer.setAmount(new BigDecimal("1000.00"));
        transfer.setPin("1234");
        transfer.setIdempotencyKey("trf-unknown");

        assertThrows(IllegalArgumentException.class,
                () -> walletService.transfer("alice@test.com", transfer));
    }
}
