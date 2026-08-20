-- SyncFailureEntityType gained USER_CREATED (UserService.resolveFromJwt's
-- Neo4j User-node creation now goes through Neo4jAsyncSyncExecutor like every
-- other graph sync, instead of a direct, unguarded GraphService call that
-- could take the whole login down with it) — same "stale CHECK constraint"
-- fix shape as V5.
ALTER TABLE sync_failures DROP CONSTRAINT sync_failures_entity_type_check;
ALTER TABLE sync_failures ADD CONSTRAINT sync_failures_entity_type_check
    CHECK (((entity_type)::text = ANY ((ARRAY['LISTENED'::character varying, 'REVIEW_RATED'::character varying, 'REVIEW_HIGHLIGHTED'::character varying, 'TRACK_RATED'::character varying, 'PLAYLIST_TRACK_ADDED'::character varying, 'PLAYLIST_TRACK_REMOVED'::character varying, 'PLAYLIST_TRACKS_REORDERED'::character varying, 'CHAT_RECOMMENDATION_MEMORY_UPDATED'::character varying, 'USER_CREATED'::character varying])::text[])));
