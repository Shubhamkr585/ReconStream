CREATE INDEX IF NOT EXISTS idx_recon_discrepancies_embedding_hnsw
ON recon_discrepancies USING hnsw (embedding vector_cosine_ops);
