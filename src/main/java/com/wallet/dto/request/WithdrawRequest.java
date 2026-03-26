package com.wallet.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class WithdrawRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "10.00", message = "Minimum withdrawal is 10.00")
    private BigDecimal amount;

    @NotBlank(message = "PIN is required")
    @Pattern(regexp = "^[0-9]{4}$", message = "Invalid PIN")
    private String pin;

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    private String description;
}
