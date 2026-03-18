package com.reconstream.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconstream.ledger.Direction;
import com.reconstream.ledger.LedgerLeg;
import com.reconstream.ledger.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private final IdempotencyClaimService claimService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;

    public PaymentService(IdempotencyClaimService claimService,
                          PaymentOrderRepository paymentOrderRepository,
                          LedgerService ledgerService,
                          ObjectMapper objectMapper) {
        this.claimService = claimService;
        this.paymentOrderRepository = paymentOrderRepository;
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<?> createPayment(String idempotencyKey, CreatePaymentRequest request) {
        String requestHash = RequestHashUtil.computeHash(request);
        IdempotencyClaimService.ClaimResult claimResult = claimService.claim(idempotencyKey, requestHash);

        if (claimResult.outcome() == IdempotencyClaimService.ClaimOutcome.EXISTS) {
            IdempotencyClaimService.ExistingRecord existing = claimResult.existingRecord();
            if (!existing.requestHash().equals(requestHash)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "IDEMPOTENCY_PAYLOAD_MISMATCH",
                                "message", "Request payload does not match the original idempotency key"));
            }
            if ("IN_PROGRESS".equals(existing.status())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "REQUEST_IN_FLIGHT",
                                "message", "A request with this idempotency key is currently processing"));
            }
            if ("COMPLETED".equals(existing.status())) {
                int status = existing.httpStatus() != null ? existing.httpStatus() : 200;
                try {
                    Object parsed = objectMapper.readValue(existing.responseBody(), Object.class);
                    return ResponseEntity.status(status).body(parsed);
                } catch (Exception e) {
                    return ResponseEntity.status(status).body(existing.responseBody());
                }
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "REQUEST_FAILED", "message", "Previous request failed"));
        }

        // Key claimed: process payment and ledger in transaction
        try {
            PaymentResponse response = executePaymentOrder(request);
            String responseJson = objectMapper.writeValueAsString(response);
            claimService.markCompleted(idempotencyKey, 201, responseJson);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            claimService.markFailed(idempotencyKey);
            throw new RuntimeException("Payment processing failed", e);
        }
    }

    @Transactional
    public PaymentResponse executePaymentOrder(CreatePaymentRequest request) {
        PaymentOrder order = new PaymentOrder(
                UUID.randomUUID(),
                request.externalOrderId(),
                request.amountPaise(),
                request.expectedMdrBps()
        );
        order = paymentOrderRepository.save(order);

        ledgerService.postJournal(
                order.getId().toString(),
                "CAPTURE",
                List.of(
                        new LedgerLeg("PSP_CLEARING", Direction.DEBIT, request.amountPaise()),
                        new LedgerLeg("MERCHANT_PAYABLE", Direction.CREDIT, request.amountPaise())
                )
        );

        return new PaymentResponse(
                order.getId(),
                order.getExternalOrderId(),
                order.getAmountPaise(),
                order.getState(),
                order.getCreatedAt()
        );
    }
}
