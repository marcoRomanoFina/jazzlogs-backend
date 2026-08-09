-- Editorial (JOINED inheritance) has no owner-type/name/image of its own —
-- AlbumEditorial/TrackEditorial/ArtistEditorial each add just their own FK
-- table. A cross-type, paginated, filterable archive listing needs all
-- three flattened into one shape; a view keeps that UNION in one place
-- instead of repeating it across every query that needs it.
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
    e.read_minutes AS read_minutes,
    e.created_at AS created_at,
    e.like_count AS like_count,
    e.featurated AS featurated
FROM editorials e
JOIN album_editorials ae ON ae.editorial_id = e.id
JOIN albums al ON al.id = ae.album_id

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
    e.featurated
FROM editorials e
JOIN track_editorials te ON te.editorial_id = e.id
JOIN tracks t ON t.id = te.track_id

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
    e.featurated
FROM editorials e
JOIN artist_editorials are ON are.editorial_id = e.id
JOIN artists ar ON ar.id = are.artist_id;
