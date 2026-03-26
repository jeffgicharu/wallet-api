package com.wallet.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class WalletResponse {
    private Long walletId;
    private String ownerName;
    private String phoneNumber;
    private BigDecimal balance;
    private String currency;
    private boolean active;
    private LocalDateTime createdAt;
}
