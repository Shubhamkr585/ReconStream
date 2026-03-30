package com.reconstream.recon;

import com.reconstream.ledger.LedgerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recon")
public class ReconController {

    private final ReconDiscrepancyRepository discrepancyRepository;
    private final LedgerService ledgerService;
    private final SettlementIngestionService settlementIngestionService;

    public ReconController(ReconDiscrepancyRepository discrepancyRepository,
                           LedgerService ledgerService,
                           SettlementIngestionService settlementIngestionService) {
        this.discrepancyRepository = discrepancyRepository;
        this.ledgerService = ledgerService;
        this.settlementIngestionService = settlementIngestionService;
    }

    @PostMapping("/settlements/upload")
    public ResponseEntity<SettlementUploadResponse> uploadSettlements(@RequestParam("file") MultipartFile file) throws IOException {
        SettlementUploadResponse response = settlementIngestionService.ingestCsv(file);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/discrepancies")
    public ResponseEntity<Page<ReconDiscrepancy>> getDiscrepancies(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(discrepancyRepository.findFiltered(type, status, pageable));
    }

    @GetMapping("/summary")
    public ResponseEntity<ReconSummaryResponse> getSummary() {
        List<ReconDiscrepancyRepository.DiscrepancySummaryProjection> stats =
                discrepancyRepository.getDiscrepancySummary();

        Map<String, ReconSummaryResponse.DiscrepancyStat> discMap = new HashMap<>();
        long totalCount = 0;
        long totalDiff = 0;

        for (var stat : stats) {
            long count = stat.getCount() != null ? stat.getCount() : 0;
            long diff = stat.getTotalDiffPaise() != null ? stat.getTotalDiffPaise() : 0;
            discMap.put(stat.getDiscrepancyType(), new ReconSummaryResponse.DiscrepancyStat(count, diff));
            totalCount += count;
            totalDiff += Math.abs(diff);
        }

        Map<String, Long> balances = Map.of(
                "PSP_CLEARING", ledgerService.getAccountBalancePaise("PSP_CLEARING"),
                "MERCHANT_PAYABLE", ledgerService.getAccountBalancePaise("MERCHANT_PAYABLE"),
                "MDR_FEE_EXPENSE", ledgerService.getAccountBalancePaise("MDR_FEE_EXPENSE"),
                "BANK_SETTLEMENT", ledgerService.getAccountBalancePaise("BANK_SETTLEMENT")
        );

        return ResponseEntity.ok(new ReconSummaryResponse(discMap, balances, totalCount, totalDiff));
    }
}
