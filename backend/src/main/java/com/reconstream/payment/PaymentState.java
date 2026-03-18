package com.reconstream.payment;

public enum PaymentState {
    INITIATED,
    PENDING,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    FAILED,
    REFUNDED
}
