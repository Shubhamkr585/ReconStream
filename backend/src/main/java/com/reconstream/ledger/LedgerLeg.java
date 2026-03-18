package com.reconstream.ledger;

public record LedgerLeg(String accountCode, Direction direction, Long amountPaise) {
}
