-- An album can be credited to more than one leading artist (e.g. "Know What
-- I Mean?" — Cannonball Adderley & Bill Evans) — albums.artist_id (one artist
-- per album) was wrong. Replaces it with an ordered join table.
CREATE TABLE album_artists (
    album_id uuid NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
    artist_id uuid NOT NULL REFERENCES artists(id) ON DELETE CASCADE,
    position integer NOT NULL,
    PRIMARY KEY (album_id, artist_id)
);

INSERT INTO album_artists (album_id, artist_id, position)
SELECT id, artist_id, 0 FROM albums WHERE artist_id IS NOT NULL;

ALTER TABLE albums DROP COLUMN artist_id;
