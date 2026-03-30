package com.reconstream.recon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recon_discrepancies")
public class ReconDiscrepancy {

    @Id
    private UUID id;

    @Column(name = "payment_order_id")
    private UUID paymentOrderId;

    @Column(name = "psp_txn_id", length = 128)
    private String pspTxnId;

    @Column(name = "discrepancy_type", nullable = false, length = 32)
    private String discrepancyType;

    @Column(name = "expected_paise", nullable = false)
    private Long expectedPaise;

    @Column(name = "actual_paise", nullable = false)
    private Long actualPaise;

    @Column(name = "diff_paise", nullable = false)
    private Long diffPaise;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "ai_summary", columnDefinition = "text")
    private String aiSummary;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ReconDiscrepancy() {
    }

    public ReconDiscrepancy(UUID id, UUID paymentOrderId, String pspTxnId, String discrepancyType,
                            Long expectedPaise, Long actualPaise, Long diffPaise, String description) {
        this.id = id != null ? id : UUID.randomUUID();
        this.paymentOrderId = paymentOrderId;
        this.pspTxnId = pspTxnId;
        this.discrepancyType = discrepancyType;
        this.expectedPaise = expectedPaise != null ? expectedPaise : 0L;
        this.actualPaise = actualPaise != null ? actualPaise : 0L;
        this.diffPaise = diffPaise != null ? diffPaise : 0L;
        this.description = description != null ? description : "";
        this.status = "OPEN";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPaymentOrderId() {
        return paymentOrderId;
    }

    public void setPaymentOrderId(UUID paymentOrderId) {
        this.paymentOrderId = paymentOrderId;
    }

    public String getPspTxnId() {
        return pspTxnId;
    }

    public void setPspTxnId(String pspTxnId) {
        this.pspTxnId = pspTxnId;
    }

    public String getDiscrepancyType() {
        return discrepancyType;
    }

    public void setDiscrepancyType(String discrepancyType) {
        this.discrepancyType = discrepancyType;
    }

    public Long getExpectedPaise() {
        return expectedPaise;
    }

    public void setExpectedPaise(Long expectedPaise) {
        this.expectedPaise = expectedPaise;
    }

    public Long getActualPaise() {
        return actualPaise;
    }

    public void setActualPaise(Long actualPaise) {
        this.actualPaise = actualPaise;
    }

    public Long getDiffPaise() {
        return diffPaise;
    }

    public void setDiffPaise(Long diffPaise) {
        this.diffPaise = diffPaise;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
