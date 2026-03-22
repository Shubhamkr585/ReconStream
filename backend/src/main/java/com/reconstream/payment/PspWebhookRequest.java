package com.reconstream.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PspWebhookRequest(
        @NotBlank String pspTxnId,
        @NotBlank String externalOrderId,
        @NotBlank String eventType,
        @NotNull @Positive Long amountPaise,
        String signature
) {}
