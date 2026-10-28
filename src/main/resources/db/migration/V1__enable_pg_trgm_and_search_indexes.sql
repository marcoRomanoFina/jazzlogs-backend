-- Backs ResolveJazzlogsEntityTool's free-text -> catalog id search
-- (see com.jazzlogs.backend.agent.ResolveJazzlogsEntityTool). Not something
-- ddl-auto=update can do (it only manages columns/tables mapped from JPA
-- entities, never extensions or non-default index types) — Flyway owns just
-- this one piece of schema, everything else stays on ddl-auto=update.
-- Postgres-only: this profile (prod) is the only one Flyway runs against —
-- see spring.flyway.enabled=false on the dev (H2) profile.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_artists_normalized_name_trgm
    ON artists USING gin (normalized_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_albums_normalized_name_trgm
    ON albums USING gin (normalized_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_tracks_normalized_name_trgm
    ON tracks USING gin (normalized_name gin_trgm_ops);
