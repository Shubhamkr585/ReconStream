package com.reconstream;

import com.reconstream.ledger.*;
import com.reconstream.payment.CreatePaymentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class IdempotencyConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerService ledgerService;

    @Test
    @DisplayName("1. 50-Thread Duplicate Storm & 2. Replay Check & 3. Payload Mismatch")
    void testConcurrencyAndIdempotency() throws Exception {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        String idempotencyKey = "storm-key-" + UUID.randomUUID();
        String externalOrderId = "order-" + UUID.randomUUID();
        Long amountPaise = 10000L;
        CreatePaymentRequest request = new CreatePaymentRequest(externalOrderId, amountPaise, 200);

        List<ResponseEntity<String>> responses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Idempotency-Key", idempotencyKey);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, headers);

                    ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/payments", entity, String.class);
                    responses.add(response);

                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        createdCount.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown(); // Unleash all 50 threads at once
        boolean completedInTime = doneLatch.await(30, TimeUnit.SECONDS);
        assertThat(completedInTime).isTrue();
        executor.shutdown();

        // 1. Assert storm responses
        assertThat(responses).hasSize(threadCount);
        assertThat(createdCount.get()).isGreaterThanOrEqualTo(1);
        for (ResponseEntity<String> resp : responses) {
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
            if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                assertThat(resp.getBody()).contains("REQUEST_IN_FLIGHT");
            }
        }

        // Assert real SQL counts
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_orders WHERE external_order_id = ?", Integer.class, externalOrderId);
        assertThat(orderCount).isEqualTo(1);

        UUID paymentOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM payment_orders WHERE external_order_id = ?", UUID.class, externalOrderId);
        assertThat(paymentOrderId).isNotNull();

        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE reference_id = ?", Integer.class, paymentOrderId.toString());
        assertThat(journalCount).isEqualTo(1);

        Integer ledgerEntryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries le JOIN journal_entries je ON le.journal_entry_id = je.id WHERE je.reference_id = ?",
                Integer.class, paymentOrderId.toString());
        assertThat(ledgerEntryCount).isEqualTo(2);

        Long balanceDrift = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_paise ELSE -amount_paise END), 0) FROM ledger_entries",
                Long.class);
        assertThat(balanceDrift).isEqualTo(0L);

        // 2. Replay Check
        HttpHeaders replayHeaders = new HttpHeaders();
        replayHeaders.set("Idempotency-Key", idempotencyKey);
        replayHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CreatePaymentRequest> replayEntity = new HttpEntity<>(request, replayHeaders);

        ResponseEntity<String> replayResponse = restTemplate.postForEntity("/api/v1/payments", replayEntity, String.class);
        assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayResponse.getBody()).contains(paymentOrderId.toString());

        Integer finalOrderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_orders WHERE external_order_id = ?", Integer.class, externalOrderId);
        assertThat(finalOrderCount).isEqualTo(1);

        // 3. Payload Mismatch Check
        CreatePaymentRequest mismatchRequest = new CreatePaymentRequest(externalOrderId, 20000L, 200);
        HttpEntity<CreatePaymentRequest> mismatchEntity = new HttpEntity<>(mismatchRequest, replayHeaders);

        ResponseEntity<String> mismatchResponse = restTemplate.postForEntity("/api/v1/payments", mismatchEntity, String.class);
        assertThat(mismatchResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mismatchResponse.getBody()).contains("IDEMPOTENCY_PAYLOAD_MISMATCH");
    }

    @Test
    @DisplayName("4. Unbalanced Ledger Check")
    void testUnbalancedLedgerRejection() {
        String refId = "unbalanced-ref-" + UUID.randomUUID();
        List<LedgerLeg> unbalancedLegs = List.of(
                new LedgerLeg("PSP_CLEARING", Direction.DEBIT, 1000L),
                new LedgerLeg("MERCHANT_PAYABLE", Direction.CREDIT, 500L)
        );

        assertThrows(UnbalancedJournalException.class, () -> {
            ledgerService.postJournal(refId, "CAPTURE", unbalancedLegs);
        });

        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE reference_id = ?", Integer.class, refId);
        assertThat(journalCount).isEqualTo(0);
    }
}
