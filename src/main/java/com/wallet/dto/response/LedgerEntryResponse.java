package com.wallet.dto.response;

import com.wallet.enums.EntryType;
import com.wallet.enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class LedgerEntryResponse {
    private Long id;
    private String transactionReference;
    private TransactionType transactionType;
    private EntryType entryType;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private LocalDateTime createdAt;
}
