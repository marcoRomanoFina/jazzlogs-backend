-- read_minutes is unused and being dropped entirely — redefine the view
-- without it first (it still references editorials.read_minutes), then drop
-- the underlying column. DROP + CREATE, not CREATE OR REPLACE: Postgres only
-- allows CREATE OR REPLACE VIEW to append columns at the end, never remove
-- one from the middle of the output list (errors "cannot drop columns from
-- view") — read_minutes sits in the middle of this view's column order.
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
    e.featurated AS featurated,
    ar.name AS context_name,
    al.release_year AS release_year,
    fb.text AS preview_text
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
    e.featurated,
    alb.name AS context_name,
    alb.release_year AS release_year,
    fb.text AS preview_text
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
    e.featurated,
    NULL AS context_name,
    NULL AS release_year,
    fb.text AS preview_text
FROM editorials e
JOIN artist_editorials are ON are.editorial_id = e.id
JOIN artists ar ON ar.id = are.artist_id
LEFT JOIN LATERAL (
    SELECT eb.text FROM editorial_blocks eb WHERE eb.editorial_id = e.id ORDER BY eb.position ASC LIMIT 1
) fb ON true;

ALTER TABLE editorials DROP COLUMN read_minutes;
