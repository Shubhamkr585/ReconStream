package com.reconstream.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreatePaymentRequest(
        @NotBlank String externalOrderId,
        @NotNull @Positive Long amountPaise,
        Integer expectedMdrBps
) {
}
