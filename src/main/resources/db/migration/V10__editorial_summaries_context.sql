-- The archive page's list/carousel views need a bit more context than "what
-- this editorial is about" (owner_name): for an album, the artist who made
-- it; for a track, the album it's on; plus the release year for both (an
-- artist editorial has neither). CREATE OR REPLACE VIEW can append columns
-- at the end without touching the existing ones.
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
    al.release_year AS release_year
FROM editorials e
JOIN album_editorials ae ON ae.editorial_id = e.id
JOIN albums al ON al.id = ae.album_id
JOIN artists ar ON ar.id = al.artist_id

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
    alb.release_year AS release_year
FROM editorials e
JOIN track_editorials te ON te.editorial_id = e.id
JOIN tracks t ON t.id = te.track_id
JOIN albums alb ON alb.id = t.album_id

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
    NULL AS release_year
FROM editorials e
JOIN artist_editorials are ON are.editorial_id = e.id
JOIN artists ar ON ar.id = are.artist_id;
