-- Reverts V35: jazzlogs-audio stays private (series may end up paywalled),
-- so playback goes through a presigned URL generated on demand
-- (AudioStorageService.presignPlaybackUrl) instead of a stored public one —
-- back to a plain S3 object key, not a URL.
ALTER TABLE series_chapters RENAME COLUMN audio_url TO audio_object_key;
