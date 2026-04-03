package com.reconstream.ai;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class AiController {

    private final DiscrepancyEmbeddingService embeddingService;
    private final AiTriageService triageService;

    public AiController(DiscrepancyEmbeddingService embeddingService, AiTriageService triageService) {
        this.embeddingService = embeddingService;
        this.triageService = triageService;
    }

    @PostMapping("/api/v1/recon/discrepancies/{id}/embed")
    public ResponseEntity<Void> generateEmbedding(@PathVariable UUID id) {
        embeddingService.embedDiscrepancy(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/recon/discrepancies/{id}/similar")
    public ResponseEntity<List<SimilarDiscrepancyResponse>> getSimilarDiscrepancies(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "0.35") double maxDistance) {
        return ResponseEntity.ok(embeddingService.findSimilarDiscrepancies(id, limit, maxDistance));
    }

    @PostMapping("/api/v1/ai/triage")
    public ResponseEntity<AiTriageResponse> triageDiscrepancy(@RequestBody AiTriageRequest request) {
        return ResponseEntity.ok(triageService.triageDiscrepancy(request.discrepancyId(), request.question()));
    }
}
