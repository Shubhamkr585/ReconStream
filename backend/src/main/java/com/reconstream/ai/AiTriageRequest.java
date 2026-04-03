package com.reconstream.ai;

import java.util.UUID;

public record AiTriageRequest(
        UUID discrepancyId,
        String question
) {}
