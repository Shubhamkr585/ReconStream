package com.reconstream.ai;

import java.util.List;
import java.util.UUID;

public record AiTriageResponse(
        UUID discrepancyId,
        String summary,
        List<String> toolsInvoked,
        List<SimilarDiscrepancyResponse> similarDiscrepancies
) {}
