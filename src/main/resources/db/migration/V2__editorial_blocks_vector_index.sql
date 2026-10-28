-- Backs EDITORIAL_SEARCH's semantic search
-- (com.jazzlogs.backend.agent.EditorialSearchTool / EditorialBlockRepository.semanticSearch).
-- Without this, every search is a full table scan computing cosine distance
-- against every row in editorial_blocks. Extension enablement is defensive,
-- not assumed: this project had no Flyway before V1, so pgvector's `vector`
-- extension being usable already (editorial_blocks.embedding has worked in
-- prod prior to this migration existing) was set up out of band, never
-- tracked here until now.
CREATE EXTENSION IF NOT EXISTS vector;

-- HNSW, not ivfflat: no need to pick a `lists` parameter sized to row count
-- up front, and it's the generally-recommended default for pgvector >= 0.5.0.
-- Cosine ops to match the <=> operator EditorialBlockRepository.semanticSearch
-- uses. If the Supabase project's pgvector version is older than 0.5.0 and
-- this fails, switch to:
--   CREATE INDEX ... ON editorial_blocks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_editorial_blocks_embedding_hnsw
    ON editorial_blocks USING hnsw (embedding vector_cosine_ops);
