-- BlockContentCategory's final value set: HISTORICAL_CONTEXT renamed to
-- CONTEXT, ANECDOTE/JAZZLOGS_JOURNEY/PERSONAL_TAKE dropped, HOOK/QUOTE added.
-- Verified before writing this: editorial_blocks is empty (the track-only
-- pivot's V47 flatten already wiped editorial content), so the UPDATE below
-- is a no-op today — kept anyway so this migration stays correct against a
-- database that isn't empty.
UPDATE editorial_blocks SET content_category = 'CONTEXT' WHERE content_category = 'HISTORICAL_CONTEXT';

ALTER TABLE editorial_blocks DROP CONSTRAINT editorial_blocks_content_category_check;
ALTER TABLE editorial_blocks ADD CONSTRAINT editorial_blocks_content_category_check
    CHECK (((content_category)::text = ANY ((ARRAY['HOOK'::character varying, 'CONTEXT'::character varying, 'MUSICAL_ANALYSIS'::character varying, 'PERSONNEL_HIGHLIGHT'::character varying, 'MOOD_AND_ATMOSPHERE'::character varying, 'RECOMMENDATION'::character varying, 'QUOTE'::character varying])::text[])));
