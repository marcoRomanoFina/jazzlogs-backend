-- Switches chapter audio duration from milliseconds to seconds — see
-- SeriesChapter.audioDurationSeconds. Client-supplied metadata (nothing
-- decodes audio to derive it), so the column just gets renamed; any
-- already-stored values are re-interpreted by the app going forward.
ALTER TABLE series_chapters RENAME COLUMN audio_duration_ms TO audio_duration_seconds;
