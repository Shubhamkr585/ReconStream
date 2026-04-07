package com.reconstream.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByJournalEntryId(UUID journalEntryId);

    @Query("""
        SELECT le FROM LedgerEntry le
        JOIN FETCH le.account a
        JOIN FETCH le.journalEntry je
        WHERE je.referenceId = :refId1 OR je.referenceId = :refId2
        ORDER BY le.createdAt ASC
    """)
    List<LedgerEntry> findByReferenceIds(@Param("refId1") String refId1, @Param("refId2") String refId2);

    @Query(value = """
        SELECT 
            a.code AS accountCode,
            a.code AS accountName,
            a.account_type AS accountType,
            COALESCE(SUM(CASE WHEN le.direction = 'DEBIT' THEN le.amount_paise ELSE 0 END), 0) AS totalDebitPaise,
            COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount_paise ELSE 0 END), 0) AS totalCreditPaise
        FROM accounts a
        LEFT JOIN ledger_entries le ON a.id = le.account_id
        GROUP BY a.id, a.code, a.account_type
        ORDER BY a.code ASC
        """, nativeQuery = true)
    List<AccountSummaryProjection> getAccountsSummary();

    interface AccountSummaryProjection {
        String getAccountCode();
        String getAccountName();
        String getAccountType();
        Long getTotalDebitPaise();
        Long getTotalCreditPaise();
    }

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
