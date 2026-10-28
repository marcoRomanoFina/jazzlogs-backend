-- Which narrator voice is credited on this playlist — see Playlist.byline
-- (same SeriesVoice enum as Series.voice). Backfilled to MARK for whatever
-- playlists already exist; new ones set it explicitly via the normal
-- POST/PUT /playlists upsert.
ALTER TABLE playlists ADD COLUMN byline character varying(255);
UPDATE playlists SET byline = 'MARK' WHERE byline IS NULL;
ALTER TABLE playlists ALTER COLUMN byline SET NOT NULL;
