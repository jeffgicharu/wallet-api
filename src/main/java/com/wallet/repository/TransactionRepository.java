package com.wallet.repository;

import com.wallet.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wallet.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.senderWallet.id = :walletId AND t.type = 'TRANSFER' AND t.status = 'COMPLETED' AND t.createdAt >= :since")
    BigDecimal sumTransfersSince(@Param("walletId") Long walletId, @Param("since") LocalDateTime since);

    long count();

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.status = 'COMPLETED'")
    BigDecimal sumAllCompleted();

    Optional<Transaction> findByReference(String reference);

    boolean existsByReference(String reference);

    @Query("SELECT t FROM Transaction t WHERE t.senderWallet.id = :walletId OR t.receiverWallet.id = :walletId ORDER BY t.createdAt DESC")
    Page<Transaction> findByWalletId(@Param("walletId") Long walletId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE (t.senderWallet.id = :walletId OR t.receiverWallet.id = :walletId) AND t.type = :type ORDER BY t.createdAt DESC")
    Page<Transaction> findByWalletIdAndType(@Param("walletId") Long walletId, @Param("type") TransactionType type, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE (t.senderWallet.id = :walletId OR t.receiverWallet.id = :walletId) AND t.createdAt BETWEEN :from AND :to ORDER BY t.createdAt DESC")
    Page<Transaction> findByWalletIdAndDateRange(@Param("walletId") Long walletId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);
}
