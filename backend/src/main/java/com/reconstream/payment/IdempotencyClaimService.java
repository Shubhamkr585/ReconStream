package com.reconstream.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class IdempotencyClaimService {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyClaimService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public enum ClaimOutcome {
        CLAIMED,
        EXISTS
    }

    public record ExistingRecord(String requestHash, String status, Integer httpStatus, String responseBody) {}

    public record ClaimResult(ClaimOutcome outcome, ExistingRecord existingRecord) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claim(String idempotencyKey, String requestHash) {
        int rows = jdbcTemplate.update(
                "INSERT INTO idempotency_keys (idempotency_key, request_hash, status) VALUES (?, ?, 'IN_PROGRESS') ON CONFLICT (idempotency_key) DO NOTHING",
                idempotencyKey, requestHash
        );

        if (rows == 1) {
            return new ClaimResult(ClaimOutcome.CLAIMED, null);
        }

        List<ExistingRecord> records = jdbcTemplate.query(
                "SELECT request_hash, status, http_status, response_body::text FROM idempotency_keys WHERE idempotency_key = ?",
                (rs, rowNum) -> new ExistingRecord(
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getObject("http_status") != null ? rs.getInt("http_status") : null,
                        rs.getString("response_body")
                ),
                idempotencyKey
        );

        if (records.isEmpty()) {
            return new ClaimResult(ClaimOutcome.CLAIMED, null);
        }

        return new ClaimResult(ClaimOutcome.EXISTS, records.get(0));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String idempotencyKey, int httpStatus, String responseBodyJson) {
        jdbcTemplate.update(
                "UPDATE idempotency_keys SET status = 'COMPLETED', http_status = ?, response_body = ?::jsonb, updated_at = NOW() WHERE idempotency_key = ?",
                httpStatus, responseBodyJson, idempotencyKey
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String idempotencyKey) {
        jdbcTemplate.update(
                "UPDATE idempotency_keys SET status = 'FAILED', updated_at = NOW() WHERE idempotency_key = ?",
                idempotencyKey
        );
    }
}
