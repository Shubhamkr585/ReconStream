package com.reconstream.ai;

import com.reconstream.payment.PaymentOrder;
import com.reconstream.payment.PaymentOrderRepository;
import com.reconstream.recon.ReconDiscrepancy;
import com.reconstream.recon.ReconDiscrepancyRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReconAuditTools {

    private final ReconDiscrepancyRepository discrepancyRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final JdbcTemplate jdbcTemplate;

    public ReconAuditTools(ReconDiscrepancyRepository discrepancyRepository,
                           PaymentOrderRepository paymentOrderRepository,
                           JdbcTemplate jdbcTemplate) {
        this.discrepancyRepository = discrepancyRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "Get discrepancy record and linked payment order details by discrepancy UUID")
    public DiscrepancyDetailsResult getDiscrepancyDetails(UUID discrepancyId) {
        Optional<ReconDiscrepancy> discOpt = discrepancyRepository.findById(discrepancyId);
        if (discOpt.isEmpty()) {
            return new DiscrepancyDetailsResult(null, null, null, null, null, null, null, null, null, null);
        }

        ReconDiscrepancy disc = discOpt.get();
        PaymentOrder order = null;
        if (disc.getPaymentOrderId() != null) {
            order = paymentOrderRepository.findById(disc.getPaymentOrderId()).orElse(null);
        } else if (disc.getPspTxnId() != null) {
            order = paymentOrderRepository.findByPspTxnId(disc.getPspTxnId()).orElse(null);
        }

        return new DiscrepancyDetailsResult(
                disc.getId(),
                disc.getDiscrepancyType(),
                disc.getExpectedPaise(),
                disc.getActualPaise(),
                disc.getDiffPaise(),
                disc.getDescription(),
                disc.getPspTxnId(),
                order != null ? order.getId() : null,
                order != null ? order.getExternalOrderId() : null,
                order != null ? order.getState().name() : null
        );
    }

    @Tool(description = "Get exact journal entries and ledger entries with debit/credit paise amounts and balance proof for an external order ID")
    public LedgerAuditResult getLedgerEntriesForOrder(String externalOrderId) {
        PaymentOrder order = paymentOrderRepository.findByExternalOrderId(externalOrderId).orElse(null);
        if (order == null) {
            return new LedgerAuditResult(externalOrderId, null, List.of(), 0L, 0L, false);
        }

        String sql = """
            SELECT le.direction, le.amount_paise, a.code AS account_code, je.event_type
            FROM ledger_entries le
            JOIN journal_entries je ON le.journal_entry_id = je.id
            JOIN accounts a ON le.account_id = a.id
            WHERE je.reference_id = ?
            ORDER BY le.created_at ASC
        """;

        List<LedgerAuditResult.LedgerLegDetail> legs = jdbcTemplate.query(sql, (rs, rowNum) ->
                new LedgerAuditResult.LedgerLegDetail(
                        rs.getString("account_code"),
                        rs.getString("direction"),
                        rs.getLong("amount_paise"),
                        rs.getString("event_type")
                ), order.getId().toString());

        long totalDebit = 0L;
        long totalCredit = 0L;
        for (var leg : legs) {
            if ("DEBIT".equalsIgnoreCase(leg.direction())) {
                totalDebit += leg.amountPaise();
            } else if ("CREDIT".equalsIgnoreCase(leg.direction())) {
                totalCredit += leg.amountPaise();
            }
        }

        boolean isBalanced = (totalDebit == totalCredit && totalDebit > 0);
        return new LedgerAuditResult(externalOrderId, order.getId(), legs, totalDebit, totalCredit, isBalanced);
    }

    @Tool(description = "Get summary of fee leakage and other discrepancies grouped by discrepancy type within lookback hours")
    public List<Map<String, Object>> getFeeLeakageSummary(int lookbackHours) {
        String sql = """
            SELECT discrepancy_type AS "discrepancyType",
                   COUNT(*) AS "count",
                   COALESCE(SUM(diff_paise), 0) AS "totalDiffPaise"
            FROM recon_discrepancies
            WHERE created_at >= NOW() - (? || ' hours')::interval
            GROUP BY discrepancy_type
        """;

        return jdbcTemplate.queryForList(sql, lookbackHours);
    }

    public record DiscrepancyDetailsResult(
            UUID discrepancyId,
            String discrepancyType,
            Long expectedPaise,
            Long actualPaise,
            Long diffPaise,
            String description,
            String pspTxnId,
            UUID orderId,
            String externalOrderId,
            String orderState
    ) {}

    public record LedgerAuditResult(
            String externalOrderId,
            UUID orderId,
            List<LedgerLegDetail> legs,
            Long totalDebitPaise,
            Long totalCreditPaise,
            boolean isBalanced
    ) {
        public record LedgerLegDetail(
                String accountCode,
                String direction,
                Long amountPaise,
                String eventType
        ) {}
    }
}
