package com.reconstream.recon;

public record SettlementRow(
        String batchId,
        String pspTxnId,
        Long grossAmountPaise,
        Long feePaise,
        Long netSettledPaise,
        String status
) {}
