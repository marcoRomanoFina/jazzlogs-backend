-- Playlists route by id, same as editorials — slug was never wired to any
-- lookup endpoint (PlaylistRepository.findBySlug had no caller).
ALTER TABLE playlists DROP CONSTRAINT ukbq229u3bbkas0bmcvn265p3b2;
ALTER TABLE playlists DROP COLUMN slug;
