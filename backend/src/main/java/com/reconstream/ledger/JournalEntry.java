package com.reconstream.ledger;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "journal_entries", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"reference_id", "event_type"})
})
public class JournalEntry {

    @Id
    private UUID id;

    @Column(name = "reference_id", nullable = false, length = 128)
    private String referenceId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public JournalEntry() {
    }

    public JournalEntry(UUID id, String referenceId, String eventType) {
        this.id = id;
        this.referenceId = referenceId;
        this.eventType = eventType;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
