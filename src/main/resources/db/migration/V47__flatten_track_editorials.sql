-- Track-only pivot: album and artist editorials are gone for good (no data
-- migration — starting from zero), and track editorial no longer shares the
-- old class-table-inheritance `editorials` base with them. Track is the only
-- kind of editorial content left, so it becomes one flat table instead.

DROP VIEW editorial_summaries;
DROP TABLE editorial_blocks;
DROP TABLE album_editorials;
DROP TABLE artist_editorials;
DROP TABLE track_editorials;
DROP TABLE editorials;

CREATE TABLE track_editorials (
    id uuid NOT NULL,
    track_id uuid NOT NULL,
    title character varying(255) NOT NULL,
    dek text,
    byline character varying(255) NOT NULL,
    image_url character varying(255),
    like_count integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT track_editorials_pkey PRIMARY KEY (id),
    CONSTRAINT uk_track_editorials_track_id UNIQUE (track_id),
    CONSTRAINT uk_track_editorials_title UNIQUE (title),
    CONSTRAINT fk_track_editorials_track FOREIGN KEY (track_id) REFERENCES tracks (id)
);

CREATE TABLE editorial_blocks (
    id uuid NOT NULL,
    editorial_id uuid NOT NULL,
    "position" integer NOT NULL,
    type character varying(255) NOT NULL,
    subhead character varying(255),
    text text NOT NULL,
    content_category character varying(255) NOT NULL,
    embedding vector(1536),
    embedding_metadata jsonb,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT editorial_blocks_pkey PRIMARY KEY (id),
    CONSTRAINT fk_editorial_blocks_track_editorial FOREIGN KEY (editorial_id) REFERENCES track_editorials (id) ON DELETE CASCADE,
    CONSTRAINT editorial_blocks_type_check
        CHECK (((type)::text = ANY ((ARRAY['LEAD'::character varying, 'PARA'::character varying, 'QUOTE'::character varying])::text[]))),
    CONSTRAINT editorial_blocks_content_category_check
        CHECK (((content_category)::text = ANY ((ARRAY['HISTORICAL_CONTEXT'::character varying, 'MUSICAL_ANALYSIS'::character varying, 'PERSONNEL_HIGHLIGHT'::character varying, 'MOOD_AND_ATMOSPHERE'::character varying, 'PERSONAL_TAKE'::character varying, 'ANECDOTE'::character varying, 'RECOMMENDATION'::character varying, 'JAZZLOGS_JOURNEY'::character varying])::text[])))
);

CREATE INDEX idx_editorial_blocks_embedding_hnsw
    ON editorial_blocks USING hnsw (embedding vector_cosine_ops);
