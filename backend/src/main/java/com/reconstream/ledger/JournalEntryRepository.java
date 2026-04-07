package com.reconstream.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
    Optional<JournalEntry> findByReferenceIdAndEventType(String referenceId, String eventType);
    boolean existsByReferenceIdAndEventType(String referenceId, String eventType);
}
