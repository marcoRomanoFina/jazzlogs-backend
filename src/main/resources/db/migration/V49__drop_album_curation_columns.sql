-- Album has no own page anymore — these were all curation for one
-- (cover/letter color, an Instagram link, the single "featured" hero slot,
-- and when it was first filed). The archive hero itself (which read
-- `featured`) was already dropped in V47.
DROP INDEX idx_albums_only_one_featured;
ALTER TABLE albums DROP COLUMN featured;
ALTER TABLE albums DROP COLUMN cover_color;
ALTER TABLE albums DROP COLUMN letter_color;
ALTER TABLE albums DROP COLUMN instagram_permalink;
ALTER TABLE albums DROP COLUMN posted_at;
