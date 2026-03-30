package com.reconstream.recon;

import java.util.Map;

public record ReconSummaryResponse(
        Map<String, DiscrepancyStat> discrepancies,
        Map<String, Long> accountBalancesPaise,
        long totalDiscrepancies,
        long totalDiscrepancyAmountPaise
) {
    public record DiscrepancyStat(long count, long totalDiffPaise) {}
}
