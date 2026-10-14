-- Same reason as V57, for the other two places a narrator character was
-- credited: a playlist's byline (V41) and the voice a series was read in
-- (V34). Irreversible: the existing values are lost.
ALTER TABLE playlists DROP COLUMN byline;
ALTER TABLE series DROP COLUMN voice;
