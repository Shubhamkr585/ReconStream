package com.reconstream.recon;

public record SettlementUploadResponse(
        String batchId,
        int rowsQueued
) {}
