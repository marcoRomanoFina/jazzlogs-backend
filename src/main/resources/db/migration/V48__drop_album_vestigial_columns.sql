-- Track-first ingestion resolves/creates Album automatically from a track's
-- own Spotify data — there's no admin form anymore to supply these, and
-- they were curation-only tags for an album's own page, which no longer
-- exists (JazzLogs is track-only now).
ALTER TABLE albums DROP COLUMN log_number;
ALTER TABLE albums DROP COLUMN label;
ALTER TABLE albums DROP COLUMN vocal_profile;
ALTER TABLE albums DROP COLUMN energy;
ALTER TABLE albums DROP COLUMN mood_intensity;
ALTER TABLE albums DROP COLUMN accessibility;
