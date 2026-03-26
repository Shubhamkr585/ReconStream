package com.reconstream.simulator;

public record ChaosRunRequest(
        Integer orderCount,
        Double duplicateRate,
        Double outOfOrderRate,
        Double dropRate
) {
    public int effectiveOrderCount() {
        return orderCount != null && orderCount > 0 ? orderCount : 20;
    }

    public double effectiveDuplicateRate() {
        return duplicateRate != null ? duplicateRate : 0.20;
    }

    public double effectiveOutOfOrderRate() {
        return outOfOrderRate != null ? outOfOrderRate : 0.15;
    }

    public double effectiveDropRate() {
        return dropRate != null ? dropRate : 0.10;
    }
}
