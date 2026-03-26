package com.reconstream.simulator;

import java.util.List;

public record ChaosSimulationSummary(
        int totalOrders,
        int droppedOrders,
        int outOfOrderOrders,
        int normalOrders,
        int duplicateOrders,
        List<OrderSimulationResult> orders
) {
    public record OrderSimulationResult(
            String externalOrderId,
            String pspTxnId,
            String groundTruthStatus,
            String scenario,
            boolean duplicated
    ) {}
}
