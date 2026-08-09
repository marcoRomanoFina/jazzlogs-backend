-- The check constraint was stuck at an old shape of BlockContentCategory
-- (ddl-auto=update creates a CHECK once at table creation and never revisits
-- it) — missing JAZZLOGS_JOURNEY, and still allowing 3 values the enum
-- dropped at some point (RECORDING_PROCESS, LEGACY_AND_INFLUENCE, COMPARISON).
-- Verified before writing this: no existing row uses any of those 3.
ALTER TABLE editorial_blocks DROP CONSTRAINT editorial_blocks_content_category_check;
ALTER TABLE editorial_blocks ADD CONSTRAINT editorial_blocks_content_category_check
    CHECK (((content_category)::text = ANY ((ARRAY['HISTORICAL_CONTEXT'::character varying, 'MUSICAL_ANALYSIS'::character varying, 'PERSONNEL_HIGHLIGHT'::character varying, 'MOOD_AND_ATMOSPHERE'::character varying, 'PERSONAL_TAKE'::character varying, 'ANECDOTE'::character varying, 'RECOMMENDATION'::character varying, 'JAZZLOGS_JOURNEY'::character varying])::text[])));
