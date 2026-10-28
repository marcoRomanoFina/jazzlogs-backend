-- DELETE /playlists/{id} deletes the playlist row directly — this lets
-- Postgres clean up its playlist_tracks rows automatically instead of the
-- service having to delete them first.
ALTER TABLE playlist_tracks DROP CONSTRAINT fkn9g4py06v2tmrisjdvxvjeb7x;
ALTER TABLE playlist_tracks ADD CONSTRAINT fkn9g4py06v2tmrisjdvxvjeb7x
    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE;
