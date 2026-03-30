package com.reconstream;

import com.reconstream.ledger.Direction;
import com.reconstream.ledger.LedgerLeg;
import com.reconstream.ledger.LedgerService;
import com.reconstream.payment.PaymentOrder;
import com.reconstream.payment.PaymentOrderRepository;
import com.reconstream.payment.PaymentState;
import com.reconstream.recon.ReconSummaryResponse;
import com.reconstream.recon.SelfHealingPollerService;
import com.reconstream.recon.SettlementUploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class ThreeWayReconIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SelfHealingPollerService selfHealingPollerService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM recon_discrepancies");
    }

    @Test
    @DisplayName("Three-Way Streaming Reconciliation, Discrepancies & Self-Healing Poller")
    void testThreeWayReconciliationPipeline() throws Exception {
        // 1. Seed 4 payment orders
        // Order A: Clean CAPTURED (100000 paise, 200 bps -> expected fee 2000 paise)
        UUID orderAId = UUID.randomUUID();
        String externalOrderA = "ord-a-" + UUID.randomUUID();
        String pspTxnA = "psp-txn-a-" + UUID.randomUUID();
        PaymentOrder orderA = new PaymentOrder(orderAId, externalOrderA, 100000L, 200);
        orderA.setPspTxnId(pspTxnA);
        orderA.setState(PaymentState.CAPTURED);
        paymentOrderRepository.save(orderA);
        ledgerService.postJournal(orderAId.toString(), "CAPTURE", List.of(
                new LedgerLeg("PSP_CLEARING", Direction.DEBIT, 100000L),
                new LedgerLeg("MERCHANT_PAYABLE", Direction.CREDIT, 100000L)
        ));

        // Order B: CAPTURED with Fee Leakage (100000 paise, 200 bps -> overcharged 2200 paise, +20 bps leakage)
        UUID orderBId = UUID.randomUUID();
        String externalOrderB = "ord-b-" + UUID.randomUUID();
        String pspTxnB = "psp-txn-b-" + UUID.randomUUID();
        PaymentOrder orderB = new PaymentOrder(orderBId, externalOrderB, 100000L, 200);
        orderB.setPspTxnId(pspTxnB);
        orderB.setState(PaymentState.CAPTURED);
        paymentOrderRepository.save(orderB);
        ledgerService.postJournal(orderBId.toString(), "CAPTURE", List.of(
                new LedgerLeg("PSP_CLEARING", Direction.DEBIT, 100000L),
                new LedgerLeg("MERCHANT_PAYABLE", Direction.CREDIT, 100000L)
        ));

        // Order C: CAPTURED with Amount Mismatch (100000 paise in DB, 95000 paise in CSV)
        UUID orderCId = UUID.randomUUID();
        String externalOrderC = "ord-c-" + UUID.randomUUID();
        String pspTxnC = "psp-txn-c-" + UUID.randomUUID();
        PaymentOrder orderC = new PaymentOrder(orderCId, externalOrderC, 100000L, 200);
        orderC.setPspTxnId(pspTxnC);
        orderC.setState(PaymentState.CAPTURED);
        paymentOrderRepository.save(orderC);
        ledgerService.postJournal(orderCId.toString(), "CAPTURE", List.of(
                new LedgerLeg("PSP_CLEARING", Direction.DEBIT, 100000L),
                new LedgerLeg("MERCHANT_PAYABLE", Direction.CREDIT, 100000L)
        ));

        // Order D: Stuck PENDING order (backdated created_at > 30s)
        UUID orderDId = UUID.randomUUID();
        String externalOrderD = "ord-d-" + UUID.randomUUID();
        PaymentOrder orderD = new PaymentOrder(orderDId, externalOrderD, 50000L, 200);
        orderD.setState(PaymentState.PENDING);
        paymentOrderRepository.save(orderD);
        // Backdate created_at in PostgreSQL
        jdbcTemplate.update("UPDATE payment_orders SET created_at = NOW() - INTERVAL '120 seconds' WHERE id = ?", orderDId);

        // 2. Upload real multipart CSV containing rows for A, B, C, plus Ghost Settlement
        String ghostPspTxnId = "ghost-psp-txn-" + UUID.randomUUID();
        String csvContent = "psp_txn_id,gross_amount_paise,fee_paise,net_settled_paise,status\n" +
                pspTxnA + ",100000,2000,98000,SETTLED\n" +
                pspTxnB + ",100000,2200,97800,SETTLED\n" +
                pspTxnC + ",95000,1900,93100,SETTLED\n" +
                ghostPspTxnId + ",50000,1000,49000,SETTLED\n";

        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource csvResource = new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "bank_settlements.csv";
            }
        };

        MultiValueMap<String, Object> uploadBody = new LinkedMultiValueMap<>();
        uploadBody.add("file", csvResource);
        // Authenticate as AUDITOR
        HttpHeaders authHeaders = new HttpHeaders();
        ResponseEntity<Map> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("email", "auditor@reconstream.dev", "password", "password123"),
                Map.class);
        List<String> setCookies = loginResp.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies != null) {
            for (String c : setCookies) {
                authHeaders.add(HttpHeaders.COOKIE, c.split(";")[0]);
                uploadHeaders.add(HttpHeaders.COOKIE, c.split(";")[0]);
            }
        }

        HttpEntity<MultiValueMap<String, Object>> uploadRequest = new HttpEntity<>(uploadBody, uploadHeaders);

        ResponseEntity<SettlementUploadResponse> uploadResp = restTemplate.postForEntity(
                "/api/v1/recon/settlements/upload", uploadRequest, SettlementUploadResponse.class);

        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(uploadResp.getBody()).isNotNull();
        assertThat(uploadResp.getBody().rowsQueued()).isEqualTo(4);

        // 3. Run Self-Healing Poller up to max attempts for Order D
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update("UPDATE payment_orders SET last_polled_at = NULL WHERE id = ?", orderDId);
            selfHealingPollerService.pollStaleOrders();
        }

        // 4. Assert via Awaitility and real SQL queries
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Order A is SETTLED with balanced 3-leg SETTLEMENT journal entry
                    PaymentOrder updatedA = paymentOrderRepository.findById(orderAId).orElseThrow();
                    assertThat(updatedA.getState()).isEqualTo(PaymentState.SETTLED);

                    Integer settlementJournalsA = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM journal_entries WHERE reference_id = ? AND event_type = 'SETTLEMENT'",
                            Integer.class, orderAId.toString());
                    assertThat(settlementJournalsA).isEqualTo(1);

                    Integer settlementLegsA = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM ledger_entries le JOIN journal_entries je ON le.journal_entry_id = je.id " +
                            "WHERE je.reference_id = ? AND je.event_type = 'SETTLEMENT'",
                            Integer.class, orderAId.toString());
                    assertThat(settlementLegsA).isEqualTo(3);

                    // Exactly 4 discrepancies persisted
                    Integer totalDiscrepancies = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM recon_discrepancies", Integer.class);
                    assertThat(totalDiscrepancies).isEqualTo(4);

                    // FEE_LEAKAGE (diff_paise = 200)
                    Long feeLeakDiff = jdbcTemplate.queryForObject(
                            "SELECT diff_paise FROM recon_discrepancies WHERE discrepancy_type = 'FEE_LEAKAGE'", Long.class);
                    assertThat(feeLeakDiff).isEqualTo(200L);

                    // AMOUNT_MISMATCH (diff_paise = -5000)
                    Long amountMismatchDiff = jdbcTemplate.queryForObject(
                            "SELECT diff_paise FROM recon_discrepancies WHERE discrepancy_type = 'AMOUNT_MISMATCH'", Long.class);
                    assertThat(amountMismatchDiff).isEqualTo(-5000L);

                    // GHOST_SETTLEMENT (diff_paise = 50000)
                    Long ghostDiff = jdbcTemplate.queryForObject(
                            "SELECT diff_paise FROM recon_discrepancies WHERE discrepancy_type = 'GHOST_SETTLEMENT'", Long.class);
                    assertThat(ghostDiff).isEqualTo(50000L);

                    // STUCK_PENDING
                    Integer stuckPendingCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM recon_discrepancies WHERE discrepancy_type = 'STUCK_PENDING'", Integer.class);
                    assertThat(stuckPendingCount).isEqualTo(1);

                    // Global ledger invariant holds strictly
                    Long balanceDrift = jdbcTemplate.queryForObject(
                            "SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount_paise ELSE -amount_paise END), 0) FROM ledger_entries",
                            Long.class);
                    assertThat(balanceDrift).isEqualTo(0L);
                });

        // 5. Re-uploading exact same CSV produces 0 additional discrepancies or journal entries
        ResponseEntity<SettlementUploadResponse> reuploadResp = restTemplate.postForEntity(
                "/api/v1/recon/settlements/upload", uploadRequest, SettlementUploadResponse.class);
        assertThat(reuploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        await().pollDelay(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer totalDiscrepanciesAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM recon_discrepancies", Integer.class);
            assertThat(totalDiscrepanciesAfter).isEqualTo(4);

            Integer totalJournalsAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM journal_entries WHERE event_type = 'SETTLEMENT'", Integer.class);
            assertThat(totalJournalsAfter).isEqualTo(2); // Order A and Order B
        });

        // 6. Verify Read APIs
        ResponseEntity<ReconSummaryResponse> summaryResp = restTemplate.exchange(
                "/api/v1/recon/summary", HttpMethod.GET, new HttpEntity<>(authHeaders), ReconSummaryResponse.class);
        assertThat(summaryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReconSummaryResponse summary = summaryResp.getBody();
        assertThat(summary).isNotNull();
        assertThat(summary.totalDiscrepancies()).isEqualTo(4);
        assertThat(summary.discrepancies()).containsKey("FEE_LEAKAGE");
        assertThat(summary.discrepancies()).containsKey("AMOUNT_MISMATCH");
        assertThat(summary.discrepancies()).containsKey("GHOST_SETTLEMENT");
        assertThat(summary.discrepancies()).containsKey("STUCK_PENDING");
        assertThat(summary.accountBalancesPaise()).containsKey("PSP_CLEARING");
        assertThat(summary.accountBalancesPaise()).containsKey("BANK_SETTLEMENT");

        ResponseEntity<String> discrepanciesResp = restTemplate.exchange(
                "/api/v1/recon/discrepancies?page=0&size=10", HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(discrepanciesResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(discrepanciesResp.getBody()).contains("FEE_LEAKAGE");
        assertThat(discrepanciesResp.getBody()).contains("AMOUNT_MISMATCH");
    }
}
