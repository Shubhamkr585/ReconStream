package com.reconstream.simulator;

public record PspStatusResponse(
        String externalOrderId,
        String pspTxnId,
        String status,
        Long amountPaise
) {}
