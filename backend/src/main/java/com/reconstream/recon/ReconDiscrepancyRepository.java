package com.reconstream.recon;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReconDiscrepancyRepository extends JpaRepository<ReconDiscrepancy, UUID> {

    boolean existsByPspTxnIdAndDiscrepancyType(String pspTxnId, String discrepancyType);
    boolean existsByPaymentOrderIdAndDiscrepancyType(UUID paymentOrderId, String discrepancyType);

    @Query("SELECT d FROM ReconDiscrepancy d WHERE " +
           "(:type IS NULL OR d.discrepancyType = :type) AND " +
           "(:status IS NULL OR d.status = :status) " +
           "ORDER BY d.createdAt DESC")
    Page<ReconDiscrepancy> findFiltered(@Param("type") String type, @Param("status") String status, Pageable pageable);

    @Query(value = "SELECT discrepancy_type AS discrepancyType, COUNT(*) AS count, COALESCE(SUM(diff_paise), 0) AS totalDiffPaise " +
                   "FROM recon_discrepancies GROUP BY discrepancy_type", nativeQuery = true)
    List<DiscrepancySummaryProjection> getDiscrepancySummary();

    interface DiscrepancySummaryProjection {
        String getDiscrepancyType();
        Long getCount();
        Long getTotalDiffPaise();
    }
}
