-- jazzlogs-audio became a public-read bucket (see docker-compose.yml), so
-- AudioStorageService now hands back a full public URL instead of a bare S3
-- object key — rename the column to match imageUrl/landscapeImageUrl/
-- coverImageUrl's naming, same meaning as those.
ALTER TABLE series_chapters RENAME COLUMN audio_object_key TO audio_url;
