package com.reconstream.ai;

import java.util.UUID;

public record SimilarDiscrepancyResponse(
        UUID id,
        String discrepancyType,
        String description,
        Long diffPaise,
        Double distance
) {}
