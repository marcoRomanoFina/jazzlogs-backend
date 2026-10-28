-- log_number used to live on albums (dropped in V48, when Album's curation
-- fields were stripped for the track-only pivot) — now each track is its own
-- log, so it belongs here instead. Verified before writing this: no existing
-- row to backfill.
ALTER TABLE track_editorials ADD COLUMN log_number character varying(255) NOT NULL;
