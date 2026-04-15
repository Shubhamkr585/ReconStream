-- ====================================================================
-- ReconStream Zero-Drift & Idempotency Audit Script
-- ====================================================================

-- 1. Check duplicate storm total created orders (strictly 300 unique orders)
SELECT 
    COUNT(*) AS total_storm_orders,
    CASE 
        WHEN COUNT(*) = 300 THEN 'PASS: Exactly 300 orders created' 
        ELSE 'FAIL: Duplicate or incomplete orders created: ' || COUNT(*) 
    END AS idempotency_status
FROM payment_orders
WHERE external_order_id LIKE 'order-storm-%';

-- 2. Verify global double-entry ledger balance (Strictly 0 drift across ALL entries)
SELECT 
    COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_paise ELSE -amount_paise END), 0) AS global_drift,
    CASE 
        WHEN COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_paise ELSE -amount_paise END), 0) = 0 
        THEN 'PASS: Strict Double-Entry Balanced (Zero Drift)' 
        ELSE 'FAIL: Global Drift Detected' 
    END AS invariant_status
FROM ledger_entries;

-- 3. Verify that every journal posting is internally balanced (sum debit == sum credit)
SELECT 
    je.reference_id,
    je.event_type,
    SUM(CASE WHEN le.direction = 'DEBIT' THEN le.amount_paise ELSE -le.amount_paise END) AS unbalanced_paise
FROM ledger_entries le
JOIN journal_entries je ON le.journal_entry_id = je.id
GROUP BY je.reference_id, je.event_type
HAVING SUM(CASE WHEN le.direction = 'DEBIT' THEN le.amount_paise ELSE -le.amount_paise END) != 0;

-- 4. Display Account Breakdown (Sum of Debits, Sum of Credits, and Net Balance)
SELECT 
    a.code AS account_code,
    SUM(CASE WHEN le.direction = 'DEBIT' THEN le.amount_paise ELSE 0 END) AS total_debit_paise,
    SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount_paise ELSE 0 END) AS total_credit_paise,
    SUM(CASE WHEN le.direction = 'DEBIT' THEN le.amount_paise ELSE -le.amount_paise END) AS net_balance_paise
FROM ledger_entries le
JOIN accounts a ON le.account_id = a.id
GROUP BY a.code
ORDER BY a.code;
