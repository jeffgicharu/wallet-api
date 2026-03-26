package com.wallet.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferRequest {

    @NotBlank(message = "Recipient phone number is required")
    @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Invalid phone number")
    private String recipientPhone;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "10.00", message = "Minimum transfer is 10.00")
    private BigDecimal amount;

    @NotBlank(message = "PIN is required")
    @Pattern(regexp = "^[0-9]{4}$", message = "Invalid PIN")
    private String pin;

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    private String description;
}
