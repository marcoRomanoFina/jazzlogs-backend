-- Reviews are gone entirely, no replacement — JazzLogs is track-only now,
-- and album ratings/highlighted-tracks curation went with them.

-- Clean up any pending retry rows for the sync-failure types this drop
-- removes, before the CHECK constraint stops allowing them.
DELETE FROM sync_failures WHERE entity_type IN ('REVIEW_RATED', 'REVIEW_HIGHLIGHTED');

ALTER TABLE sync_failures DROP CONSTRAINT sync_failures_entity_type_check;
ALTER TABLE sync_failures ADD CONSTRAINT sync_failures_entity_type_check
    CHECK (((entity_type)::text = ANY ((ARRAY['LISTENED'::character varying, 'TRACK_RATED'::character varying, 'PLAYLIST_TRACK_ADDED'::character varying, 'PLAYLIST_TRACK_REMOVED'::character varying, 'PLAYLIST_TRACKS_REORDERED'::character varying, 'PLAYLIST_DELETED'::character varying, 'CHAT_RECOMMENDATION_MEMORY_UPDATED'::character varying, 'USER_CREATED'::character varying])::text[])));

ALTER TABLE likes DROP CONSTRAINT likes_entity_type_check;
ALTER TABLE likes ADD CONSTRAINT likes_entity_type_check
    CHECK (((entity_type)::text = ANY ((ARRAY['EDITORIAL'::character varying, 'PLAYLIST'::character varying, 'NOTE'::character varying, 'SERIES'::character varying])::text[])));

DROP TABLE review_standout_tracks;
DROP TABLE reviews;
