-- Drops editorials.featurated now that albums.featured (see V24) replaces
-- it as the archive hero's source of truth. The view has to be dropped and
-- recreated, not CREATE OR REPLACE VIEW: that only allows appending columns
-- at the end, not removing one from the middle (same reasoning as V12's own
-- DROP+CREATE) — this is the same view V19 last touched, just without
-- e.featurated in any of its three branches.
DROP VIEW editorial_summaries;

CREATE VIEW editorial_summaries AS
SELECT
    e.id AS id,
    'ALBUM' AS owner_type,
    al.id AS owner_id,
    al.name AS owner_name,
    al.image_url AS owner_image_url,
    e.title AS title,
    e.dek AS dek,
    e.byline AS byline,
    e.created_at AS created_at,
    e.like_count AS like_count,
    ar.name AS context_name,
    al.release_year AS release_year,
    fb.text AS preview_text,
    ar.id AS context_id,
    al.log_number AS log_number
FROM editorials e
JOIN album_editorials ae ON ae.editorial_id = e.id
JOIN albums al ON al.id = ae.album_id
JOIN artists ar ON ar.id = al.artist_id
LEFT JOIN LATERAL (
    SELECT eb.text FROM editorial_blocks eb WHERE eb.editorial_id = e.id ORDER BY eb.position ASC LIMIT 1
) fb ON true

UNION ALL

SELECT
    e.id,
    'TRACK',
    t.id,
    t.name,
    t.image_url,
    e.title,
    e.dek,
    e.byline,
    e.created_at,
    e.like_count,
    alb.name AS context_name,
    alb.release_year AS release_year,
    fb.text AS preview_text,
    alb.id AS context_id,
    alb.log_number AS log_number
FROM editorials e
JOIN track_editorials te ON te.editorial_id = e.id
JOIN tracks t ON t.id = te.track_id
JOIN albums alb ON alb.id = t.album_id
LEFT JOIN LATERAL (
    SELECT eb.text FROM editorial_blocks eb WHERE eb.editorial_id = e.id ORDER BY eb.position ASC LIMIT 1
) fb ON true

UNION ALL

SELECT
    e.id,
    'ARTIST',
    ar.id,
    ar.name,
    ar.image_url,
    e.title,
    e.dek,
    e.byline,
    e.created_at,
    e.like_count,
    NULL AS context_name,
    NULL AS release_year,
    fb.text AS preview_text,
    NULL AS context_id,
    NULL AS log_number
FROM editorials e
JOIN artist_editorials are ON are.editorial_id = e.id
JOIN artists ar ON ar.id = are.artist_id
LEFT JOIN LATERAL (
    SELECT eb.text FROM editorial_blocks eb WHERE eb.editorial_id = e.id ORDER BY eb.position ASC LIMIT 1
) fb ON true;

DROP INDEX idx_editorials_only_one_featured;

ALTER TABLE editorials DROP COLUMN featurated;
