package com.wallet.repository;

import com.wallet.entity.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Page<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(Long walletId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.entryType = 'DEBIT'")
    BigDecimal sumAllDebits();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.entryType = 'CREDIT'")
    BigDecimal sumAllCredits();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.wallet.id = :walletId AND e.entryType = 'DEBIT'")
    BigDecimal sumDebitsByWallet(@Param("walletId") Long walletId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.wallet.id = :walletId AND e.entryType = 'CREDIT'")
    BigDecimal sumCreditsByWallet(@Param("walletId") Long walletId);
}
