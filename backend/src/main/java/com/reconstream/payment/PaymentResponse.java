package com.reconstream.payment;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID paymentOrderId,
        String externalOrderId,
        Long amountPaise,
        PaymentState state,
        Instant createdAt
) {
}
