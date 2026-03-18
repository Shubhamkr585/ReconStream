package com.reconstream.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByJournalEntryId(UUID journalEntryId);

    @Query(value = """
        SELECT COALESCE(
            SUM(
                CASE 
                    WHEN (a.account_type IN ('ASSET', 'EXPENSE') AND le.direction = 'DEBIT')
                         OR (a.account_type IN ('LIABILITY', 'REVENUE') AND le.direction = 'CREDIT')
                    THEN le.amount_paise
                    ELSE -le.amount_paise
                END
            ), 0
        )
        FROM ledger_entries le
        JOIN accounts a ON le.account_id = a.id
        WHERE a.code = :accountCode
        """, nativeQuery = true)
    Long calculateAccountBalancePaise(@Param("accountCode") String accountCode);
}
