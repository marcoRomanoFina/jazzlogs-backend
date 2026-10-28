-- Three more playlist-level images, distinct from cover_image_url (the
-- catalogue/card thumbnail) — used to lay out the playlist detail page.
ALTER TABLE playlists ADD COLUMN principal_image_url character varying(255);
ALTER TABLE playlists ADD COLUMN banner_image_url character varying(255);
ALTER TABLE playlists ADD COLUMN footer_image_url character varying(255);
