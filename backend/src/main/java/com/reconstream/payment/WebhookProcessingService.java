package com.reconstream.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconstream.ledger.Direction;
import com.reconstream.ledger.JournalEntryRepository;
import com.reconstream.ledger.LedgerLeg;
import com.reconstream.ledger.LedgerService;
import com.reconstream.outbox.OutboxEvent;
import com.reconstream.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WebhookProcessingService {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessingService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final LedgerService ledgerService;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Counter ignoredCounter;

    @Value("${reconstream.webhook.secret:reconstream-psp-webhook-secret-key-2026}")
    private String webhookSecret;

    public WebhookProcessingService(PaymentOrderRepository paymentOrderRepository,
                                  LedgerService ledgerService,
                                  JournalEntryRepository journalEntryRepository,
                                  OutboxEventRepository outboxEventRepository,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.ledgerService = ledgerService;
        this.journalEntryRepository = journalEntryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.ignoredCounter = meterRegistry.counter("reconstream.webhooks.ignored.total");
    }

    public ResponseEntity<?> processWebhook(PspWebhookRequest request, String headerSignature) {
        String signatureToVerify = request.signature() != null ? request.signature() : headerSignature;
        boolean validSignature = HmacUtil.verifySignature(
                request.pspTxnId(),
                request.externalOrderId(),
                request.eventType(),
                request.amountPaise(),
                signatureToVerify,
                webhookSecret
        );

        if (!validSignature) {
            log.warn("Invalid webhook signature for order {}", request.externalOrderId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "INVALID_SIGNATURE", "message", "Webhook signature verification failed"));
        }

        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return executeWebhookTransaction(request);
            } catch (OptimisticLockingFailureException ex) {
                log.warn("Optimistic locking conflict on attempt {} for order {}", attempt, request.externalOrderId());
                if (attempt == maxRetries - 1) {
                    throw ex;
                }
                try {
                    Thread.sleep(50 * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResponseEntity<?> executeWebhookTransaction(PspWebhookRequest request) {
        PaymentOrder order = paymentOrderRepository.findByExternalOrderId(request.externalOrderId()).orElse(null);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "ORDER_NOT_FOUND", "message", "Payment order not found: " + request.externalOrderId()));
        }

        PaymentState targetState;
        try {
            targetState = PaymentState.valueOf(request.eventType());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_EVENT_TYPE", "message", "Unsupported event type: " + request.eventType()));
        }

        PaymentState.TransitionResult transitionResult = order.getState().evaluateTransition(targetState);

        if (transitionResult == PaymentState.TransitionResult.STALE_OR_DUPLICATE ||
            transitionResult == PaymentState.TransitionResult.INVALID) {
            log.info("Ignoring stale or duplicate webhook event {} for order {} in state {}",
                    request.eventType(), request.externalOrderId(), order.getState());
            ignoredCounter.increment();
            return ResponseEntity.ok(Map.of("status", "IGNORED_STALE_OR_DUPLICATE"));
        }

        // Valid transition
        order.setState(targetState);
        if (request.pspTxnId() != null && !request.pspTxnId().isBlank()) {
            order.setPspTxnId(request.pspTxnId());
        }
        order.setUpdatedAt(Instant.now());
        paymentOrderRepository.save(order);

        // Atomically post CAPTURE journal entry if needed
        if (targetState == PaymentState.CAPTURED) {
            boolean alreadyPosted = journalEntryRepository.existsByReferenceIdAndEventType(order.getId().toString(), "CAPTURE");
            if (!alreadyPosted) {
                ledgerService.postJournal(
                        order.getId().toString(),
                        "CAPTURE",
                        List.of(
                                new LedgerLeg("PSP_CLEARING", Direction.DEBIT, request.amountPaise()),
                                new LedgerLeg("MERCHANT_PAYABLE", Direction.CREDIT, request.amountPaise())
                        )
                );
            }
        }

        // Insert PENDING row into outbox_events in the EXACT same DB transaction
        try {
            Map<String, Object> payloadMap = Map.of(
                    "orderId", order.getId().toString(),
                    "externalOrderId", order.getExternalOrderId(),
                    "pspTxnId", order.getPspTxnId() != null ? order.getPspTxnId() : "",
                    "eventType", "PAYMENT_" + targetState.name(),
                    "state", targetState.name(),
                    "amountPaise", order.getAmountPaise()
            );
            String payloadJson = objectMapper.writeValueAsString(payloadMap);
            OutboxEvent outboxEvent = new OutboxEvent(
                    UUID.randomUUID(),
                    "PAYMENT_ORDER",
                    order.getId().toString(),
                    "PAYMENT_" + targetState.name(),
                    payloadJson
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "state", targetState.name(),
                "orderId", order.getId().toString()
        ));
    }
}
