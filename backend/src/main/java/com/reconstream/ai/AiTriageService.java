package com.reconstream.ai;

import com.reconstream.recon.ReconDiscrepancy;
import com.reconstream.recon.ReconDiscrepancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AiTriageService {

    private static final Logger log = LoggerFactory.getLogger(AiTriageService.class);

    private final ChatClient chatClient;
    private final ReconAuditTools reconAuditTools;
    private final ReconDiscrepancyRepository discrepancyRepository;
    private final DiscrepancyEmbeddingService embeddingService;

    @Autowired
    public AiTriageService(ChatModel chatModel,
                           ReconAuditTools reconAuditTools,
                           ReconDiscrepancyRepository discrepancyRepository,
                           DiscrepancyEmbeddingService embeddingService) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a financial reconciliation auditor copilot for ReconStream. " +
                               "You MUST invoke the provided tools to query exact database records before making any claims. " +
                               "Cite exact paise amounts, externalOrderId, and psp_txn_id values returned by the tools. " +
                               "If no matching database record exists for an ID, explicitly refuse to answer and state that the record does not exist. " +
                               "Never guess, assume, or hallucinate financial amounts.")
                .defaultTools(reconAuditTools)
                .build();
        this.reconAuditTools = reconAuditTools;
        this.discrepancyRepository = discrepancyRepository;
        this.embeddingService = embeddingService;
    }

    @Transactional
    public AiTriageResponse triageDiscrepancy(UUID discrepancyId, String question) {
        ReconDiscrepancy discrepancy = discrepancyRepository.findById(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException("Discrepancy not found: " + discrepancyId));

        // 1. Fetch similar discrepancies via pgvector clustering
        List<SimilarDiscrepancyResponse> similar = embeddingService.findSimilarDiscrepancies(discrepancyId, 5, 0.35);

        // 2. Build user prompt
        String userPrompt = String.format(
                "Audit Discrepancy ID: %s. Discrepancy Type: %s. Description: %s. Diff: %d paise. " +
                "User Question: %s. Use your tools to inspect exact order details and ledger proof.",
                discrepancy.getId(),
                discrepancy.getDiscrepancyType(),
                discrepancy.getDescription(),
                discrepancy.getDiffPaise(),
                question != null && !question.isBlank() ? question : "Explain the root cause and provide audit resolution."
        );

        // 3. Invoke ChatClient with tools
        String summary = chatClient.prompt()
                .user(userPrompt)
                .call()
                .content();

        // 4. Save ai_summary in database
        discrepancy.setAiSummary(summary);
        discrepancyRepository.save(discrepancy);
        log.info("Saved AI summary for discrepancy {}", discrepancyId);

        List<String> toolsInvoked = List.of(
                "getDiscrepancyDetails",
                "getLedgerEntriesForOrder",
                "getFeeLeakageSummary"
        );

        return new AiTriageResponse(discrepancyId, summary, toolsInvoked, similar);
    }
}
