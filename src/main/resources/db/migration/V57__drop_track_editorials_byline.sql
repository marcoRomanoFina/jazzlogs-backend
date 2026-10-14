-- Logs are no longer signed. JazzLogs writes in one editorial voice and the
-- narrator characters are gone, so a byline has nothing left to say — and a
-- column that could only ever hold one value is not worth keeping.
-- Irreversible: which character had signed each existing log is lost.
ALTER TABLE track_editorials DROP COLUMN byline;
