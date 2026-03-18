package com.reconstream.payment;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_orders")
public class PaymentOrder {

    @Id
    private UUID id;

    @Column(name = "external_order_id", nullable = false, unique = true, length = 128)
    private String externalOrderId;

    @Column(name = "psp_txn_id", unique = true, length = 128)
    private String pspTxnId;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "expected_mdr_bps", nullable = false)
    private Integer expectedMdrBps = 200;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentState state = PaymentState.INITIATED;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "poll_attempts", nullable = false)
    private Integer pollAttempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public PaymentOrder() {
    }

    public PaymentOrder(UUID id, String externalOrderId, Long amountPaise, Integer expectedMdrBps) {
        this.id = id;
        this.externalOrderId = externalOrderId;
        this.amountPaise = amountPaise;
        this.expectedMdrBps = expectedMdrBps != null ? expectedMdrBps : 200;
        this.state = PaymentState.INITIATED;
        this.version = 0L;
        this.pollAttempts = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getExternalOrderId() {
        return externalOrderId;
    }

    public void setExternalOrderId(String externalOrderId) {
        this.externalOrderId = externalOrderId;
    }

    public String getPspTxnId() {
        return pspTxnId;
    }

    public void setPspTxnId(String pspTxnId) {
        this.pspTxnId = pspTxnId;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public void setAmountPaise(Long amountPaise) {
        this.amountPaise = amountPaise;
    }

    public Integer getExpectedMdrBps() {
        return expectedMdrBps;
    }

    public void setExpectedMdrBps(Integer expectedMdrBps) {
        this.expectedMdrBps = expectedMdrBps;
    }

    public PaymentState getState() {
        return state;
    }

    public void setState(PaymentState state) {
        this.state = state;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }

    public void setLastPolledAt(Instant lastPolledAt) {
        this.lastPolledAt = lastPolledAt;
    }

    public Integer getPollAttempts() {
        return pollAttempts;
    }

    public void setPollAttempts(Integer pollAttempts) {
        this.pollAttempts = pollAttempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
