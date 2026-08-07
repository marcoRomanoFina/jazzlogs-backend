-- The archive page's featured/lead card wants a snippet of the actual
-- editorial body, not just the dek. Blocks live in their own table
-- (editorial_blocks) with an embedding column we don't want anywhere near
-- this view — a LATERAL join pulls just the first block's text (by
-- position), nothing else, one row per editorial either way.
CREATE OR REPLACE VIEW editorial_summaries AS
SELECT
    e.id AS id,
    'ALBUM' AS owner_type,
    al.id AS owner_id,
    al.name AS owner_name,
    al.image_url AS owner_image_url,
    e.title AS title,
    e.dek AS dek,
    e.byline AS byline,
    e.read_minutes AS read_minutes,
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
    e.read_minutes,
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
    e.read_minutes,
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
