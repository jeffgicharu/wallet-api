package com.wallet.dto.response;

import com.wallet.enums.TransactionStatus;
import com.wallet.enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class TransactionResponse {
    private String reference;
    private TransactionType type;
    private TransactionStatus status;
    private BigDecimal amount;
    private BigDecimal fee;
    private String senderPhone;
    private String receiverPhone;
    private String description;
    private LocalDateTime createdAt;
}
