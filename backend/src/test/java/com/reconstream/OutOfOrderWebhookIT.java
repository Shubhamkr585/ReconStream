package com.reconstream;

import com.reconstream.outbox.OutboxRelayService;
import com.reconstream.payment.HmacUtil;
import com.reconstream.payment.PaymentOrder;
import com.reconstream.payment.PaymentOrderRepository;
import com.reconstream.payment.PaymentState;
import com.reconstream.payment.PspWebhookRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class OutOfOrderWebhookIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OutOfOrderWebhookIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Value("${reconstream.webhook.secret:reconstream-psp-webhook-secret-key-2026}")
    private String webhookSecret;

    @Test
    @DisplayName("Deterministic Webhook State Machine & Transactional Outbox to Redpanda")
    void testOutOfOrderAndDuplicateWebhooksWithOutbox() {
        // 1. Create a payment order (INITIATED)
        UUID orderId = UUID.randomUUID();
        String externalOrderId = "order-ooo-" + UUID.randomUUID();
        Long amountPaise = 15000L;

        PaymentOrder order = new PaymentOrder(orderId, externalOrderId, amountPaise, 200);
        order.setState(PaymentState.INITIATED);
        paymentOrderRepository.save(order);

        // 2. Send CAPTURED webhook (forward jump over AUTHORIZED)
        String pspTxnId = "psp-txn-" + UUID.randomUUID();
        String capturedSig = HmacUtil.computeSignature(pspTxnId, externalOrderId, "CAPTURED", amountPaise, webhookSecret);
        PspWebhookRequest capturedReq = new PspWebhookRequest(pspTxnId, externalOrderId, "CAPTURED", amountPaise, capturedSig);

        ResponseEntity<String> capturedResp = restTemplate.postForEntity("/api/v1/webhooks/psp", capturedReq, String.class);
        assertThat(capturedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedResp.getBody()).contains("SUCCESS");

        PaymentOrder capturedOrder = paymentOrderRepository.findByExternalOrderId(externalOrderId).orElseThrow();
        assertThat(capturedOrder.getState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(capturedOrder.getPspTxnId()).isEqualTo(pspTxnId);

        // 3. Send delayed AUTHORIZED webhook -> assert 200 OK (IGNORED_STALE_OR_DUPLICATE) and state remains CAPTURED
        String authorizedSig = HmacUtil.computeSignature(pspTxnId, externalOrderId, "AUTHORIZED", amountPaise, webhookSecret);
        PspWebhookRequest authorizedReq = new PspWebhookRequest(pspTxnId, externalOrderId, "AUTHORIZED", amountPaise, authorizedSig);

        ResponseEntity<String> authorizedResp = restTemplate.postForEntity("/api/v1/webhooks/psp", authorizedReq, String.class);
        assertThat(authorizedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorizedResp.getBody()).contains("IGNORED_STALE_OR_DUPLICATE");

        PaymentOrder stillCapturedOrder = paymentOrderRepository.findByExternalOrderId(externalOrderId).orElseThrow();
        assertThat(stillCapturedOrder.getState()).isEqualTo(PaymentState.CAPTURED);

        // 4. Send duplicate CAPTURED webhook -> assert 200 OK (IGNORED_STALE_OR_DUPLICATE)
        ResponseEntity<String> duplicateCapturedResp = restTemplate.postForEntity("/api/v1/webhooks/psp", capturedReq, String.class);
        assertThat(duplicateCapturedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(duplicateCapturedResp.getBody()).contains("IGNORED_STALE_OR_DUPLICATE");

        // Verify via SQL that journal_entries has exactly 1 CAPTURE row and SUM(DEBIT) - SUM(CREDIT) == 0
        Integer captureJournalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE reference_id = ? AND event_type = 'CAPTURE'",
                Integer.class, orderId.toString());
        assertThat(captureJournalCount).isEqualTo(1);

        Long balanceDrift = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_paise ELSE -amount_paise END), 0) FROM ledger_entries",
                Long.class);
        assertThat(balanceDrift).isEqualTo(0L);

        // 5. Trigger/await the Outbox Relay (using Awaitility, NEVER Thread.sleep)
        // and assert outbox_events transitions to PUBLISHED and real Kafka consumer receives event
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ooo-it-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList("payment.events"));

            // Await Outbox Relay transitions status to PUBLISHED using Awaitility
            await().atMost(15, TimeUnit.SECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        outboxRelayService.relayPendingEvents();
                        String outboxStatus = jdbcTemplate.queryForObject(
                                "SELECT status FROM outbox_events WHERE aggregate_id = ?",
                                String.class, orderId.toString());
                        assertThat(outboxStatus).isEqualTo("PUBLISHED");
                    });

            // Poll Kafka consumer for the published event
            boolean found = false;
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline && !found) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    log.info("Consumer polled Kafka record: key={}, value={}", record.key(), record.value());
                    if (orderId.toString().equals(record.key()) || record.value().contains(externalOrderId)) {
                        assertThat(record.value()).contains("PAYMENT_CAPTURED");
                        assertThat(record.value()).contains(externalOrderId);
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("Expected Kafka event for order %s", orderId).isTrue();
        }
    }

    @Test
    @DisplayName("Invalid Webhook Signature is Rejected with 401 Unauthorized")
    void testInvalidWebhookSignatureRejected() {
        PspWebhookRequest badReq = new PspWebhookRequest(
                "psp-bad-123",
                "order-bad-123",
                "CAPTURED",
                5000L,
                "bad-signature-hex-12345"
        );

        ResponseEntity<String> resp = restTemplate.postForEntity("/api/v1/webhooks/psp", badReq, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_SIGNATURE");
    }

    private HttpHeaders loginAs(String email, String password) {
        Map<String, String> creds = Map.of("email", email, "password", password);
        ResponseEntity<Map> resp = restTemplate.postForEntity("/api/v1/auth/login", creds, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies != null) {
            for (String c : cookies) {
                headers.add(HttpHeaders.COOKIE, c.split(";")[0]);
            }
        }
        return headers;
    }

    @Test
    @DisplayName("Chaos PSP Simulator executes simulation and exposes ground-truth status")
    void testChaosSimulator() {
        HttpHeaders headers = loginAs("admin@reconstream.dev", "password123");
        com.reconstream.simulator.ChaosRunRequest req = new com.reconstream.simulator.ChaosRunRequest(5, 0.20, 0.20, 0.20);
        HttpEntity<com.reconstream.simulator.ChaosRunRequest> entity = new HttpEntity<>(req, headers);
        ResponseEntity<com.reconstream.simulator.ChaosSimulationSummary> resp = restTemplate.postForEntity(
                "/api/v1/simulator/chaos-run", entity, com.reconstream.simulator.ChaosSimulationSummary.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        com.reconstream.simulator.ChaosSimulationSummary summary = resp.getBody();
        assertThat(summary).isNotNull();
        assertThat(summary.totalOrders()).isEqualTo(5);
        assertThat(summary.orders()).hasSize(5);

        String sampleOrderId = summary.orders().get(0).externalOrderId();
        ResponseEntity<com.reconstream.simulator.PspStatusResponse> statusResp = restTemplate.exchange(
                "/api/v1/simulator/psp-status/" + sampleOrderId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                com.reconstream.simulator.PspStatusResponse.class);

        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        com.reconstream.simulator.PspStatusResponse status = statusResp.getBody();
        assertThat(status).isNotNull();
        assertThat(status.externalOrderId()).isEqualTo(sampleOrderId);
        assertThat(status.status()).isEqualTo("CAPTURED");
    }
}
