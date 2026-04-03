package com.reconstream;

import com.reconstream.ai.AiTriageRequest;
import com.reconstream.ai.AiTriageResponse;
import com.reconstream.ai.DiscrepancyEmbeddingService;
import com.reconstream.ai.ReconAuditTools;
import com.reconstream.ai.SimilarDiscrepancyResponse;
import com.reconstream.ledger.Direction;
import com.reconstream.ledger.LedgerLeg;
import com.reconstream.ledger.LedgerService;
import com.reconstream.payment.PaymentOrder;
import com.reconstream.payment.PaymentOrderRepository;
import com.reconstream.payment.PaymentState;
import com.reconstream.recon.ReconDiscrepancy;
import com.reconstream.recon.ReconDiscrepancyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

public class AiTriageAndVectorIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReconDiscrepancyRepository discrepancyRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private DiscrepancyEmbeddingService embeddingService;

    @Autowired
    private ReconAuditTools reconAuditTools;

    @MockBean
    private ChatModel chatModel;

    private HttpHeaders getAdminAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<Map> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("email", "admin@reconstream.dev", "password", "password123"),
                Map.class);
        List<String> setCookies = loginResp.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies != null) {
            for (String c : setCookies) {
                headers.add(HttpHeaders.COOKIE, c.split(";")[0]);
            }
        }
        return headers;
    }

    @Test
    @DisplayName("pgvector Cosine Similarity Clustering returns nearest neighbors and filters distant vectors")
    void testPgvectorCosineClustering() {
        // Discrepancy A (target query discrepancy): unit vector along axis 0
        UUID idA = UUID.randomUUID();
        ReconDiscrepancy discA = new ReconDiscrepancy(
                idA, null, "psp-vec-A-" + idA, "FEE_LEAKAGE", 2000L, 2200L, 200L, "Fee leakage order A");
        discrepancyRepository.save(discA);
        float[] vecA = new float[768];
        vecA[0] = 1.0f;
        embeddingService.saveEmbedding(idA, vecA);

        // Discrepancy B (close neighbor): vector with ~0.999 cosine similarity (distance ~0.001 < 0.1)
        UUID idB = UUID.randomUUID();
        ReconDiscrepancy discB = new ReconDiscrepancy(
                idB, null, "psp-vec-B-" + idB, "FEE_LEAKAGE", 2000L, 2200L, 200L, "Fee leakage order B");
        discrepancyRepository.save(discB);
        float[] vecB = new float[768];
        vecB[0] = 0.999f;
        vecB[1] = (float) Math.sqrt(1.0 - 0.999 * 0.999);
        embeddingService.saveEmbedding(idB, vecB);

        // Discrepancy C (orthogonal / distant): unit vector along axis 1 (cosine distance = 1.0 > 0.35)
        UUID idC = UUID.randomUUID();
        ReconDiscrepancy discC = new ReconDiscrepancy(
                idC, null, "psp-vec-C-" + idC, "GHOST_SETTLEMENT", 0L, 50000L, 50000L, "Ghost settlement C");
        discrepancyRepository.save(discC);
        float[] vecC = new float[768];
        vecC[1] = 1.0f;
        embeddingService.saveEmbedding(idC, vecC);

        // Query similar discrepancies for Discrepancy A with maxDistance = 0.35
        ResponseEntity<List<SimilarDiscrepancyResponse>> response = restTemplate.exchange(
                "/api/v1/recon/discrepancies/" + idA + "/similar?limit=5&maxDistance=0.35",
                HttpMethod.GET,
                new HttpEntity<>(getAdminAuthHeaders()),
                new ParameterizedTypeReference<List<SimilarDiscrepancyResponse>>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SimilarDiscrepancyResponse> results = response.getBody();
        assertThat(results).isNotNull();
        // Should find only Discrepancy B (distance < 0.1), while Discrepancy C (distance 1.0) is excluded
        assertThat(results).hasSize(1);
        SimilarDiscrepancyResponse topMatch = results.get(0);
        assertThat(topMatch.id()).isEqualTo(idB);
        assertThat(topMatch.distance()).isLessThan(0.1);
        assertThat(topMatch.discrepancyType()).isEqualTo("FEE_LEAKAGE");
    }

    @Test
    @DisplayName("Recon Audit Tools execute real grounded SQL against PostgreSQL ledger and orders")
    void testReconAuditToolsGroundedSql() {
        UUID orderId = UUID.randomUUID();
        String externalOrderId = "ORD-AUDIT-" + UUID.randomUUID();
        String pspTxnId = "psp-audit-" + UUID.randomUUID();

        PaymentOrder order = new PaymentOrder(orderId, externalOrderId, 100000L, 200);
        order.setState(PaymentState.SETTLED);
        order.setPspTxnId(pspTxnId);
        paymentOrderRepository.save(order);

        // Post balanced ledger entries: DEBIT PSP_CLEARING 100000, CREDIT MERCHANT_PAYABLE 100000
        ledgerService.postJournal(
                orderId.toString(),
                "CAPTURE",
                List.of(
                        new LedgerLeg("PSP_CLEARING", Direction.DEBIT, 100000L),
                        new LedgerLeg("MERCHANT_PAYABLE", Direction.CREDIT, 100000L)
                )
        );

        UUID discId = UUID.randomUUID();
        ReconDiscrepancy discrepancy = new ReconDiscrepancy(
                discId, orderId, pspTxnId, "FEE_LEAKAGE", 2000L, 2200L, 200L, "Fee leakage audit test for " + externalOrderId);
        discrepancyRepository.save(discrepancy);

        // 1. Tool: getDiscrepancyDetails
        var details = reconAuditTools.getDiscrepancyDetails(discId);
        assertThat(details.discrepancyId()).isEqualTo(discId);
        assertThat(details.orderId()).isEqualTo(orderId);
        assertThat(details.externalOrderId()).isEqualTo(externalOrderId);
        assertThat(details.diffPaise()).isEqualTo(200L);
        assertThat(details.pspTxnId()).isEqualTo(pspTxnId);
        assertThat(details.orderState()).isEqualTo("SETTLED");

        // 2. Tool: getLedgerEntriesForOrder
        var ledgerResult = reconAuditTools.getLedgerEntriesForOrder(externalOrderId);
        assertThat(ledgerResult.externalOrderId()).isEqualTo(externalOrderId);
        assertThat(ledgerResult.orderId()).isEqualTo(orderId);
        assertThat(ledgerResult.isBalanced()).isTrue();
        assertThat(ledgerResult.totalDebitPaise()).isEqualTo(100000L);
        assertThat(ledgerResult.totalCreditPaise()).isEqualTo(100000L);
        assertThat(ledgerResult.legs()).hasSize(2);

        // 3. Tool: getFeeLeakageSummary
        List<Map<String, Object>> summary = reconAuditTools.getFeeLeakageSummary(24);
        assertThat(summary).isNotEmpty();
        boolean foundFeeLeakage = summary.stream()
                .anyMatch(m -> "FEE_LEAKAGE".equalsIgnoreCase(String.valueOf(m.get("discrepancyType"))));
        assertThat(foundFeeLeakage).isTrue();
    }

    @Test
    @DisplayName("AI Triage Copilot queries tools, generates grounded audit summary, and persists to PostgreSQL")
    void testAiTriageEndpointAndPersistence() {
        UUID orderId = UUID.randomUUID();
        String externalOrderId = "ORD-TRIAGE-" + UUID.randomUUID();
        String pspTxnId = "psp-triage-" + UUID.randomUUID();

        PaymentOrder order = new PaymentOrder(orderId, externalOrderId, 100000L, 200);
        order.setState(PaymentState.SETTLED);
        order.setPspTxnId(pspTxnId);
        paymentOrderRepository.save(order);

        UUID discId = UUID.randomUUID();
        ReconDiscrepancy discrepancy = new ReconDiscrepancy(
                discId, orderId, pspTxnId, "FEE_LEAKAGE", 2000L, 2200L, 200L, "Fee leakage triage test");
        discrepancyRepository.save(discrepancy);

        // Seed embedding for the discrepancy so similar lookup succeeds
        float[] vec = new float[768];
        vec[0] = 0.95f;
        embeddingService.saveEmbedding(discId, vec);

        // Mock ChatModel response
        String expectedAiSummary = String.format(
                "Root Cause: Fee leakage of 200 paise verified. PSP Txn ID: %s. Net ledger balanced.", pspTxnId);
        AssistantMessage assistantMessage = new AssistantMessage(expectedAiSummary);
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        Mockito.when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        // Invoke POST /api/v1/ai/triage
        AiTriageRequest request = new AiTriageRequest(discId, "Audit this fee leakage discrepancy and verify ledger");
        ResponseEntity<AiTriageResponse> response = restTemplate.exchange(
                "/api/v1/ai/triage",
                HttpMethod.POST,
                new HttpEntity<>(request, getAdminAuthHeaders()),
                AiTriageResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AiTriageResponse triageResponse = response.getBody();
        assertThat(triageResponse).isNotNull();
        assertThat(triageResponse.discrepancyId()).isEqualTo(discId);
        assertThat(triageResponse.summary()).contains("Root Cause: Fee leakage of 200 paise verified");
        assertThat(triageResponse.toolsInvoked()).contains("getDiscrepancyDetails", "getLedgerEntriesForOrder", "getFeeLeakageSummary");

        // Verify PostgreSQL persistence of ai_summary
        ReconDiscrepancy updatedDiscrepancy = discrepancyRepository.findById(discId).orElseThrow();
        assertThat(updatedDiscrepancy.getAiSummary()).isEqualTo(expectedAiSummary);
    }
}
