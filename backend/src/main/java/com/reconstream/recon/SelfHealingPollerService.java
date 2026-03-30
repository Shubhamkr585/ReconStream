package com.reconstream.recon;

import com.reconstream.ledger.Direction;
import com.reconstream.ledger.JournalEntryRepository;
import com.reconstream.ledger.LedgerLeg;
import com.reconstream.ledger.LedgerService;
import com.reconstream.payment.PaymentOrder;
import com.reconstream.payment.PaymentOrderRepository;
import com.reconstream.payment.PaymentState;
import com.reconstream.simulator.ChaosSimulatorService;
import com.reconstream.simulator.PspStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SelfHealingPollerService {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingPollerService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final ReconDiscrepancyRepository discrepancyRepository;
    private final LedgerService ledgerService;
    private final JournalEntryRepository journalEntryRepository;
    private final ChaosSimulatorService simulatorService;

    @Value("${reconstream.poller.stale-after-seconds:30}")
    private long staleAfterSeconds;

    @Value("${reconstream.poller.max-attempts:3}")
    private int maxAttempts;

    public SelfHealingPollerService(PaymentOrderRepository paymentOrderRepository,
                                  ReconDiscrepancyRepository discrepancyRepository,
                                  LedgerService ledgerService,
                                  JournalEntryRepository journalEntryRepository,
                                  ChaosSimulatorService simulatorService) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.ledgerService = ledgerService;
        this.journalEntryRepository = journalEntryRepository;
        this.simulatorService = simulatorService;
    }

    @Scheduled(fixedDelay = 5000)
    public void scheduledPoll() {
        pollStaleOrders();
    }

    @Transactional
    public int pollStaleOrders() {
        Instant threshold = Instant.now().minusSeconds(staleAfterSeconds);
        List<PaymentOrder> staleOrders = paymentOrderRepository.findStaleOrdersForUpdate(threshold);

        if (staleOrders.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (PaymentOrder order : staleOrders) {
            int attempts = order.getPollAttempts() != null ? order.getPollAttempts() : 0;
            if (order.getLastPolledAt() != null) {
                long backoffSeconds = (long) Math.pow(2, Math.min(attempts, 6));
                if (Instant.now().isBefore(order.getLastPolledAt().plusSeconds(backoffSeconds))) {
                    continue; // Backoff not expired yet
                }
            }

            // Query PSP status
            PspStatusResponse pspStatus = simulatorService.getGroundTruthStatus(order.getExternalOrderId());

            if (pspStatus != null && "CAPTURED".equalsIgnoreCase(pspStatus.status())) {
                // Heal order: transition to CAPTURED and post capture ledger entry
                order.setState(PaymentState.CAPTURED);
                if (order.getPspTxnId() == null || order.getPspTxnId().isBlank()) {
                    order.setPspTxnId(pspStatus.pspTxnId());
                }
                order.setUpdatedAt(Instant.now());
                order.setLastPolledAt(Instant.now());
                order.setPollAttempts(attempts + 1);
                paymentOrderRepository.save(order);

                boolean alreadyPosted = journalEntryRepository.existsByReferenceIdAndEventType(order.getId().toString(), "CAPTURE");
                if (!alreadyPosted) {
                    ledgerService.postJournal(
                            order.getId().toString(),
                            "CAPTURE",
                            List.of(
                                    new LedgerLeg("PSP_CLEARING", Direction.DEBIT, order.getAmountPaise()),
                                    new LedgerLeg("MERCHANT_PAYABLE", Direction.CREDIT, order.getAmountPaise())
                            )
                    );
                }
                log.info("Self-healed order {} -> CAPTURED", order.getExternalOrderId());
                processed++;
            } else {
                int nextAttempts = attempts + 1;
                order.setPollAttempts(nextAttempts);
                order.setLastPolledAt(Instant.now());
                order.setUpdatedAt(Instant.now());
                paymentOrderRepository.save(order);

                if (nextAttempts >= maxAttempts) {
                    if (!discrepancyRepository.existsByPaymentOrderIdAndDiscrepancyType(order.getId(), "STUCK_PENDING")) {
                        ReconDiscrepancy stuck = new ReconDiscrepancy(
                                UUID.randomUUID(),
                                order.getId(),
                                order.getPspTxnId(),
                                "STUCK_PENDING",
                                order.getAmountPaise(),
                                0L,
                                order.getAmountPaise(),
                                String.format("Order stuck in %s after %d polling attempts", order.getState(), nextAttempts)
                        );
                        discrepancyRepository.save(stuck);
                        log.warn("Recorded STUCK_PENDING discrepancy for order {}", order.getExternalOrderId());
                    }
                }
                processed++;
            }
        }
        return processed;
    }
}
