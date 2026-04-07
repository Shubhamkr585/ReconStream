package com.reconstream.ledger;

import com.reconstream.payment.PaymentOrder;
import com.reconstream.payment.PaymentOrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentOrderRepository paymentOrderRepository;

    public LedgerController(LedgerEntryRepository ledgerEntryRepository,
                            PaymentOrderRepository paymentOrderRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.paymentOrderRepository = paymentOrderRepository;
    }

    public record LedgerEntryDto(
            UUID id,
            String referenceId,
            String eventType,
            String accountCode,
            String accountName,
            String direction,
            Long amountPaise,
            Instant createdAt
    ) {}

    public record LedgerOrderInspectionResponse(
            String externalOrderId,
            String paymentOrderId,
            long totalDebitPaise,
            long totalCreditPaise,
            long driftPaise,
            boolean balanced,
            List<LedgerEntryDto> entries
    ) {}

    public record AccountSummaryDto(
            String accountCode,
            String accountName,
            String accountType,
            long totalDebitPaise,
            long totalCreditPaise,
            long netBalancePaise
    ) {}

    @GetMapping("/order/{externalOrderId}")
    public ResponseEntity<LedgerOrderInspectionResponse> inspectOrderLedger(@PathVariable String externalOrderId) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByExternalOrderId(externalOrderId);
        String paymentOrderId = orderOpt.map(o -> o.getId().toString()).orElse(externalOrderId);

        List<LedgerEntry> entries = ledgerEntryRepository.findByReferenceIds(paymentOrderId, externalOrderId);

        long totalDebits = 0;
        long totalCredits = 0;
        List<LedgerEntryDto> dtos = new java.util.ArrayList<>();

        for (LedgerEntry e : entries) {
            long amt = e.getAmountPaise() != null ? e.getAmountPaise() : 0;
            if (e.getDirection() == Direction.DEBIT) {
                totalDebits += amt;
            } else {
                totalCredits += amt;
            }
            dtos.add(new LedgerEntryDto(
                    e.getId(),
                    e.getJournalEntry().getReferenceId(),
                    e.getJournalEntry().getEventType(),
                    e.getAccount().getCode(),
                    e.getAccount().getCode(),
                    e.getDirection().name(),
                    amt,
                    e.getCreatedAt()
            ));
        }

        long drift = totalDebits - totalCredits;
        boolean balanced = (drift == 0);

        return ResponseEntity.ok(new LedgerOrderInspectionResponse(
                externalOrderId,
                paymentOrderId,
                totalDebits,
                totalCredits,
                drift,
                balanced,
                dtos
        ));
    }

    @GetMapping("/accounts/summary")
    public ResponseEntity<List<AccountSummaryDto>> getAccountsSummary() {
        var projections = ledgerEntryRepository.getAccountsSummary();
        List<AccountSummaryDto> results = projections.stream().map(p -> {
            long debits = p.getTotalDebitPaise() != null ? p.getTotalDebitPaise() : 0L;
            long credits = p.getTotalCreditPaise() != null ? p.getTotalCreditPaise() : 0L;
            long net;
            if ("ASSET".equalsIgnoreCase(p.getAccountType()) || "EXPENSE".equalsIgnoreCase(p.getAccountType())) {
                net = debits - credits;
            } else {
                net = credits - debits;
            }
            return new AccountSummaryDto(
                    p.getAccountCode(),
                    p.getAccountName(),
                    p.getAccountType(),
                    debits,
                    credits,
                    net
            );
        }).toList();

        return ResponseEntity.ok(results);
    }
}
