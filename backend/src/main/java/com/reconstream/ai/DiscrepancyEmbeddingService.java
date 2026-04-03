package com.reconstream.ai;

import com.reconstream.recon.ReconDiscrepancy;
import com.reconstream.recon.ReconDiscrepancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class DiscrepancyEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyEmbeddingService.class);

    private final ReconDiscrepancyRepository discrepancyRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public DiscrepancyEmbeddingService(ReconDiscrepancyRepository discrepancyRepository,
                                       JdbcTemplate jdbcTemplate,
                                       @Autowired(required = false) EmbeddingModel embeddingModel) {
        this.discrepancyRepository = discrepancyRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    @Transactional
    public float[] embedDiscrepancy(UUID discrepancyId) {
        ReconDiscrepancy discrepancy = discrepancyRepository.findById(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException("Discrepancy not found: " + discrepancyId));

        float[] vector;
        if (embeddingModel != null) {
            String text = String.format("%s | %s | diff=%d",
                    discrepancy.getDiscrepancyType(),
                    discrepancy.getDescription(),
                    discrepancy.getDiffPaise());
            vector = embeddingModel.embed(text);
        } else {
            vector = new float[768];
            vector[0] = 1.0f;
        }

        saveEmbedding(discrepancyId, vector);
        return vector;
    }

    @Transactional
    public void saveEmbedding(UUID discrepancyId, float[] vector) {
        String vectorStr = toVectorString(vector);
        jdbcTemplate.update("UPDATE recon_discrepancies SET embedding = CAST(? AS vector) WHERE id = ?",
                vectorStr, discrepancyId);
        log.info("Saved 768-dim embedding for discrepancy {}", discrepancyId);
    }

    @Transactional(readOnly = true)
    public List<SimilarDiscrepancyResponse> findSimilarDiscrepancies(UUID discrepancyId, int limit, double maxDistance) {
        String queryVector = jdbcTemplate.query(
                "SELECT embedding::text FROM recon_discrepancies WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                discrepancyId);

        if (queryVector == null || queryVector.isBlank()) {
            return Collections.emptyList();
        }

        String sql = """
            SELECT id, discrepancy_type, description, diff_paise,
                   (embedding <=> CAST(? AS vector)) AS distance
            FROM recon_discrepancies
            WHERE id != ?
              AND embedding IS NOT NULL
              AND (embedding <=> CAST(? AS vector)) <= ?
            ORDER BY distance ASC
            LIMIT ?
        """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new SimilarDiscrepancyResponse(
                (UUID) rs.getObject("id"),
                rs.getString("discrepancy_type"),
                rs.getString("description"),
                rs.getLong("diff_paise"),
                rs.getDouble("distance")
        ), queryVector, discrepancyId, queryVector, maxDistance, limit);
    }

    public static String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
