-- Which narrator the user is talking to in this chat — see Chat.narrator.
-- Fixed for the chat's whole life: it's chosen with the first message and
-- drives the agent's voice on every later turn. Any chat that predates this
-- is backfilled to MARK, same convention as V34 (series.voice) and V41
-- (playlists.byline).
ALTER TABLE chats ADD COLUMN narrator character varying(255);
UPDATE chats SET narrator = 'MARK' WHERE narrator IS NULL;
ALTER TABLE chats ALTER COLUMN narrator SET NOT NULL;

-- JAZZLOGS is gone as a byline: EditorialByline and SeriesVoice are now one
-- enum, JazzlogsCharacter, with only the eight narrators. Nothing is signed
-- JAZZLOGS anymore, but a row left over from V43's backfill would fail to
-- load at all, so any that remain move to MARK too.
UPDATE track_editorials SET byline = 'MARK' WHERE byline = 'JAZZLOGS';
