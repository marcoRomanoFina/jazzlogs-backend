-- A log's page now shows only its cover and principal images: the secondary,
-- banner and footer slots V52 added are gone. Only the URLs go with them;
-- the files already uploaded stay in storage.
ALTER TABLE track_editorials DROP COLUMN secondary_image_url;
ALTER TABLE track_editorials DROP COLUMN banner_image_url;
ALTER TABLE track_editorials DROP COLUMN footer_image_url;
