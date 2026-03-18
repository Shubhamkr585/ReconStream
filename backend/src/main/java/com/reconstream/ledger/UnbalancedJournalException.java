package com.reconstream.ledger;

public class UnbalancedJournalException extends RuntimeException {
    public UnbalancedJournalException(String message) {
        super(message);
    }
}
