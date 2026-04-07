package com.reconstream.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(AccountRepository accountRepository,
                         JournalEntryRepository journalEntryRepository,
                         LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public JournalEntry postJournal(String referenceId, String eventType, List<LedgerLeg> legs) {
        if (legs == null || legs.size() < 2) {
            throw new UnbalancedJournalException("Journal entry must have at least 2 legs");
        }

        long totalDebit = 0;
        long totalCredit = 0;

        for (LedgerLeg leg : legs) {
            if (leg.amountPaise() == null || leg.amountPaise() <= 0) {
                throw new UnbalancedJournalException("Amount must be strictly positive for account " + leg.accountCode());
            }
            if (leg.direction() == Direction.DEBIT) {
                totalDebit += leg.amountPaise();
            } else if (leg.direction() == Direction.CREDIT) {
                totalCredit += leg.amountPaise();
            } else {
                throw new UnbalancedJournalException("Invalid direction for account " + leg.accountCode());
            }
        }

        if (totalDebit != totalCredit || totalDebit == 0) {
            throw new UnbalancedJournalException(
                    String.format("Unbalanced journal: total DEBIT (%d) != total CREDIT (%d)", totalDebit, totalCredit));
        }

        Optional<JournalEntry> existing = journalEntryRepository.findByReferenceIdAndEventType(referenceId, eventType);
        if (existing.isPresent()) {
            return existing.get();
        }

        JournalEntry journalEntry = new JournalEntry(UUID.randomUUID(), referenceId, eventType);
        journalEntry = journalEntryRepository.save(journalEntry);

        List<LedgerEntry> entries = new ArrayList<>();
        for (LedgerLeg leg : legs) {
            Account account = accountRepository.findByCode(leg.accountCode())
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + leg.accountCode()));
            entries.add(new LedgerEntry(UUID.randomUUID(), journalEntry, account, leg.amountPaise(), leg.direction()));
        }
        ledgerEntryRepository.saveAll(entries);

        return journalEntry;
    }

    @Transactional(readOnly = true)
    public Long getAccountBalancePaise(String accountCode) {
        Account account = accountRepository.findByCode(accountCode)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountCode));
        return ledgerEntryRepository.calculateAccountBalancePaise(account.getCode());
    }
}
