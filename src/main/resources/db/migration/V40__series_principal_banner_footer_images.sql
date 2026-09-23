-- Three more series-level images, distinct from cover_image_url (the
-- catalogue/card thumbnail) — used to lay out the series detail page.
ALTER TABLE series ADD COLUMN principal_image_url character varying(255);
ALTER TABLE series ADD COLUMN banner_image_url character varying(255);
ALTER TABLE series ADD COLUMN footer_image_url character varying(255);
