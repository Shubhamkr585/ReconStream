package com.reconstream.simulator;

import com.reconstream.payment.HmacUtil;
import com.reconstream.payment.PaymentOrder;
import com.reconstream.payment.PaymentOrderRepository;
import com.reconstream.payment.PaymentState;
import com.reconstream.payment.PspWebhookRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ChaosSimulatorService implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ChaosSimulatorService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final RestClient restClient;
    private final ConcurrentMap<String, PspStatusResponse> pspTruthStore = new ConcurrentHashMap<>();

    private int serverPort = 8080;

    @Value("${reconstream.webhook.secret:reconstream-psp-webhook-secret-key-2026}")
    private String webhookSecret;

    public ChaosSimulatorService(PaymentOrderRepository paymentOrderRepository) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.restClient = RestClient.create();
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.serverPort = event.getWebServer().getPort();
        log.info("Simulator initialized with server port: {}", this.serverPort);
    }

    public void setServerPort(int port) {
        this.serverPort = port;
    }

    public PspStatusResponse getGroundTruthStatus(String externalOrderId) {
        return pspTruthStore.get(externalOrderId);
    }

    public ChaosSimulationSummary runSimulation(ChaosRunRequest request) {
        if (request == null) {
            request = new ChaosRunRequest(20, 0.20, 0.15, 0.10);
        }

        int totalCount = request.effectiveOrderCount();
        double dupRate = request.effectiveDuplicateRate();
        double outOfOrderRate = request.effectiveOutOfOrderRate();
        double dropRate = request.effectiveDropRate();

        List<ChaosSimulationSummary.OrderSimulationResult> results = new ArrayList<>();
        int dropped = 0;
        int outOfOrder = 0;
        int normal = 0;
        int duplicates = 0;

        for (int i = 0; i < totalCount; i++) {
            String externalOrderId = "ORD-SIM-" + UUID.randomUUID();
            String pspTxnId = "PSP-TXN-" + UUID.randomUUID();
            Long amountPaise = 10000L;

            // 1. Create real payment order in PENDING
            PaymentOrder order = new PaymentOrder(
                    UUID.randomUUID(),
                    externalOrderId,
                    amountPaise,
                    200
            );
            order.setState(PaymentState.PENDING);
            paymentOrderRepository.save(order);

            // 2. Register ground truth in simulator
            PspStatusResponse truth = new PspStatusResponse(externalOrderId, pspTxnId, "CAPTURED", amountPaise);
            pspTruthStore.put(externalOrderId, truth);

            // 3. Roll chaos dice
            double roll = ThreadLocalRandom.current().nextDouble();
            String scenario;
            boolean duplicated = false;

            if (roll < dropRate) {
                scenario = "DROPPED";
                dropped++;
                // Drops webhook -> order stays in PENDING
            } else if (roll < dropRate + outOfOrderRate) {
                scenario = "OUT_OF_ORDER";
                outOfOrder++;
                sendWebhook(pspTxnId, externalOrderId, "CAPTURED", amountPaise);
                sendWebhook(pspTxnId, externalOrderId, "AUTHORIZED", amountPaise);
            } else {
                scenario = "NORMAL";
                normal++;
                sendWebhook(pspTxnId, externalOrderId, "AUTHORIZED", amountPaise);
                sendWebhook(pspTxnId, externalOrderId, "CAPTURED", amountPaise);
            }

            if (!"DROPPED".equals(scenario) && ThreadLocalRandom.current().nextDouble() < dupRate) {
                duplicated = true;
                duplicates++;
                sendWebhook(pspTxnId, externalOrderId, "CAPTURED", amountPaise);
            }

            results.add(new ChaosSimulationSummary.OrderSimulationResult(
                    externalOrderId, pspTxnId, "CAPTURED", scenario, duplicated
            ));
        }

        return new ChaosSimulationSummary(totalCount, dropped, outOfOrder, normal, duplicates, results);
    }

    private void sendWebhook(String pspTxnId, String externalOrderId, String eventType, Long amountPaise) {
        String signature = HmacUtil.computeSignature(pspTxnId, externalOrderId, eventType, amountPaise, webhookSecret);
        PspWebhookRequest request = new PspWebhookRequest(
                pspTxnId,
                externalOrderId,
                eventType,
                amountPaise,
                signature
        );

        String url = "http://localhost:" + serverPort + "/api/v1/webhooks/psp";
        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Simulator HTTP error sending webhook to {}: {}", url, e.getMessage());
        }
    }
}
