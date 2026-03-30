package com.reconstream.recon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconstream.config.KafkaConfig;
import com.reconstream.ledger.Direction;
import com.reconstream.ledger.JournalEntryRepository;
import com.reconstream.ledger.LedgerLeg;
import com.reconstream.ledger.LedgerService;
import com.reconstream.payment.PaymentOrder;
import com.reconstream.payment.PaymentOrderRepository;
import com.reconstream.payment.PaymentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReconConsumerService {

    private static final Logger log = LoggerFactory.getLogger(ReconConsumerService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final ReconDiscrepancyRepository discrepancyRepository;
    private final LedgerService ledgerService;
    private final JournalEntryRepository journalEntryRepository;
    private final ObjectMapper objectMapper;

    public ReconConsumerService(PaymentOrderRepository paymentOrderRepository,
                                ReconDiscrepancyRepository discrepancyRepository,
                                LedgerService ledgerService,
                                JournalEntryRepository journalEntryRepository,
                                ObjectMapper objectMapper) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.ledgerService = ledgerService;
        this.journalEntryRepository = journalEntryRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_BANK_SETTLEMENTS, groupId = "recon-engine")
    @Transactional
    public void consumeSettlementEvent(String payload) {
        SettlementRow row;
        try {
            row = objectMapper.readValue(payload, SettlementRow.class);
        } catch (Exception e) {
            log.error("Failed to deserialize settlement JSON payload: {}", payload, e);
            return;
        }

        processSettlement(row);
    }

    @Transactional
    public void processSettlement(SettlementRow row) {
        String pspTxnId = row.pspTxnId();
        Long grossAmount = row.grossAmountPaise();
        Long fee = row.feePaise();
        Long netSettled = row.netSettledPaise();

        // 1. Ghost Settlement Check
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByPspTxnId(pspTxnId);
        if (orderOpt.isEmpty()) {
            if (!discrepancyRepository.existsByPspTxnIdAndDiscrepancyType(pspTxnId, "GHOST_SETTLEMENT")) {
                ReconDiscrepancy ghost = new ReconDiscrepancy(
                        UUID.randomUUID(),
                        null,
                        pspTxnId,
                        "GHOST_SETTLEMENT",
                        0L,
                        grossAmount,
                        grossAmount,
                        "Settlement received for unknown PSP transaction ID: " + pspTxnId
                );
                discrepancyRepository.save(ghost);
                log.warn("Recorded GHOST_SETTLEMENT for pspTxnId {}", pspTxnId);
            }
            return;
        }

        PaymentOrder order = orderOpt.get();

        // 2. Amount Mismatch Check
        if (!grossAmount.equals(order.getAmountPaise())) {
            long diff = grossAmount - order.getAmountPaise();
            if (!discrepancyRepository.existsByPaymentOrderIdAndDiscrepancyType(order.getId(), "AMOUNT_MISMATCH")) {
                ReconDiscrepancy mismatch = new ReconDiscrepancy(
                        UUID.randomUUID(),
                        order.getId(),
                        pspTxnId,
                        "AMOUNT_MISMATCH",
                        order.getAmountPaise(),
                        grossAmount,
                        diff,
                        String.format("Gross amount in settlement (%d) does not match order amount (%d)", grossAmount, order.getAmountPaise())
                );
                discrepancyRepository.save(mismatch);
                log.warn("Recorded AMOUNT_MISMATCH for order {}: diff {}", order.getId(), diff);
            }
            return;
        }

        // Invariant check: net_settled_paise + fee_paise == gross_amount_paise
        if (netSettled + fee != grossAmount) {
            log.error("Unbalanced settlement legs for order {}: net ({}) + fee ({}) != gross ({})",
                    order.getId(), netSettled, fee, grossAmount);
            return;
        }

        // 3. MDR Fee Leakage Check (Strict Integer Math — NO double/float)
        long expectedFeePaise = Math.floorDiv(order.getAmountPaise() * order.getExpectedMdrBps(), 10_000L);
        if (fee > expectedFeePaise) {
            long feeLeakageDiff = fee - expectedFeePaise;
            if (!discrepancyRepository.existsByPaymentOrderIdAndDiscrepancyType(order.getId(), "FEE_LEAKAGE")) {
                ReconDiscrepancy feeLeakage = new ReconDiscrepancy(
                        UUID.randomUUID(),
                        order.getId(),
                        pspTxnId,
                        "FEE_LEAKAGE",
                        expectedFeePaise,
                        fee,
                        feeLeakageDiff,
                        String.format("Actual fee (%d) exceeds expected fee (%d) by %d paise", fee, expectedFeePaise, feeLeakageDiff)
                );
                discrepancyRepository.save(feeLeakage);
                log.warn("Recorded FEE_LEAKAGE for order {}: diff {}", order.getId(), feeLeakageDiff);
            }
        }

        // 4. Clean Settlement Posting
        order.setState(PaymentState.SETTLED);
        order.setUpdatedAt(Instant.now());
        paymentOrderRepository.save(order);

        // Check if settlement journal already exists to guarantee idempotency
        boolean alreadySettled = journalEntryRepository.existsByReferenceIdAndEventType(order.getId().toString(), "SETTLEMENT");
        if (!alreadySettled) {
            ledgerService.postJournal(
                    order.getId().toString(),
                    "SETTLEMENT",
                    List.of(
                            new LedgerLeg("BANK_SETTLEMENT", Direction.DEBIT, netSettled),
                            new LedgerLeg("MDR_FEE_EXPENSE", Direction.DEBIT, fee),
                            new LedgerLeg("PSP_CLEARING", Direction.CREDIT, grossAmount)
                    )
            );
            log.info("Posted SETTLEMENT journal for order {}", order.getId());
        }
    }
}
