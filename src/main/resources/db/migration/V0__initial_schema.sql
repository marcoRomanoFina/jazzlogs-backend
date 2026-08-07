-- Full schema baseline, reverse-engineered from the real Supabase database
-- (built up to this point via Hibernate ddl-auto=update, never previously
-- captured as a migration). From here on, ddl-auto is off (spring.jpa.
-- hibernate.ddl-auto=none) — Flyway is the only thing allowed to change
-- the schema. Runs as V0 (below V1/V2, which are already applied against
-- the real database and must keep their version numbers and checksums
-- untouched) so that a fresh, empty database bootstraps the exact same
-- schema V1/V2 already assume exists. On the real database this migration
-- is a no-op: baseline-version=0 means Flyway skips anything at version <= 0.
--
-- vector must exist before editorial_blocks.embedding can be declared as
-- vector(1536) below (pg_trgm is created later, inside V1 itself, since V0
-- doesn't need it directly).
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE album_editorials (
    album_id uuid NOT NULL,
    editorial_id uuid NOT NULL
);

CREATE TABLE albums (
    id uuid NOT NULL,
    accessibility character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    energy character varying(255) NOT NULL,
    image_url character varying(255),
    instagram_permalink character varying(255),
    label character varying(255) NOT NULL,
    log_number character varying(255) NOT NULL,
    mood_intensity character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    normalized_name character varying(255) NOT NULL,
    posted_at timestamp(6) with time zone,
    release_year integer,
    spotify_album_id character varying(255),
    spotify_url character varying(255),
    total_tracks integer,
    updated_at timestamp(6) with time zone NOT NULL,
    vocal_profile character varying(255) NOT NULL,
    artist_id uuid NOT NULL,
    CONSTRAINT albums_accessibility_check CHECK (((accessibility)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying])::text[]))),
    CONSTRAINT albums_energy_check CHECK (((energy)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying])::text[]))),
    CONSTRAINT albums_mood_intensity_check CHECK (((mood_intensity)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying])::text[]))),
    CONSTRAINT albums_vocal_profile_check CHECK (((vocal_profile)::text = ANY ((ARRAY['INSTRUMENTAL'::character varying, 'VOCAL'::character varying])::text[])))
);

CREATE TABLE artist_editorials (
    editorial_id uuid NOT NULL,
    artist_id uuid NOT NULL
);

CREATE TABLE artists (
    id uuid NOT NULL,
    name character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    image_url character varying(255),
    normalized_name character varying(255) NOT NULL,
    spotify_artist_id character varying(255),
    spotify_url character varying(255),
    updated_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE chat_exchanges (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    final_response text NOT NULL,
    user_message text NOT NULL,
    winners jsonb,
    chat_id uuid NOT NULL
);

CREATE TABLE chat_recommendation_memory (
    id uuid NOT NULL,
    chat_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    session_summary text,
    updated_at timestamp(6) with time zone NOT NULL,
    winners_history jsonb
);

CREATE TABLE chats (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    last_message_at timestamp(6) with time zone,
    title character varying(255),
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL
);

-- No "title" column — dropped before this baseline was written (see
-- EditorialBlock.java). "subhead" is optional, replacing SUBHEAD as its own
-- EditorialBlockType (see the type check constraint below).
CREATE TABLE editorial_blocks (
    id uuid NOT NULL,
    "position" integer NOT NULL,
    text text NOT NULL,
    type character varying(255) NOT NULL,
    content_category character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    embedding_metadata jsonb,
    updated_at timestamp(6) with time zone NOT NULL,
    editorial_id uuid NOT NULL,
    embedding vector(1536),
    subhead character varying(255),
    CONSTRAINT editorial_blocks_content_category_check CHECK (((content_category)::text = ANY ((ARRAY['HISTORICAL_CONTEXT'::character varying, 'RECORDING_PROCESS'::character varying, 'MUSICAL_ANALYSIS'::character varying, 'PERSONNEL_HIGHLIGHT'::character varying, 'MOOD_AND_ATMOSPHERE'::character varying, 'LEGACY_AND_INFLUENCE'::character varying, 'COMPARISON'::character varying, 'PERSONAL_TAKE'::character varying, 'ANECDOTE'::character varying, 'RECOMMENDATION'::character varying])::text[]))),
    CONSTRAINT editorial_blocks_type_check CHECK (((type)::text = ANY ((ARRAY['LEAD'::character varying, 'PARA'::character varying, 'QUOTE'::character varying])::text[])))
);

CREATE TABLE editorials (
    id uuid NOT NULL,
    byline character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    dek text NOT NULL,
    like_count integer NOT NULL,
    read_minutes integer NOT NULL,
    title character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE likes (
    user_id uuid NOT NULL,
    entity_type character varying(255) NOT NULL,
    entity_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT likes_entity_type_check CHECK (((entity_type)::text = ANY ((ARRAY['EDITORIAL'::character varying, 'REVIEW'::character varying, 'PLAYLIST'::character varying, 'NOTE'::character varying, 'SERIES'::character varying])::text[])))
);

CREATE TABLE listens (
    user_id uuid NOT NULL,
    entity_type character varying(255) NOT NULL,
    entity_id uuid NOT NULL,
    listened_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT listens_entity_type_check CHECK (((entity_type)::text = ANY ((ARRAY['ALBUM'::character varying, 'TRACK'::character varying, 'PLAYLIST'::character varying, 'SERIES_CHAPTER'::character varying])::text[])))
);

CREATE TABLE notes (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    like_count integer NOT NULL,
    text text NOT NULL,
    timestamp_seconds integer,
    track_id uuid NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE playlist_tracks (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    curator_note text,
    "position" integer NOT NULL,
    title character varying(255),
    playlist_id uuid NOT NULL,
    track_id uuid NOT NULL
);

CREATE TABLE playlists (
    id uuid NOT NULL,
    cover_image_url character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    description text,
    duration_ms bigint NOT NULL,
    like_count integer NOT NULL,
    is_published boolean NOT NULL,
    slug character varying(255) NOT NULL,
    spotify_url character varying(255),
    tagline character varying(255),
    title character varying(255) NOT NULL,
    track_count integer NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE review_standout_tracks (
    review_id uuid NOT NULL,
    track_id uuid NOT NULL
);

CREATE TABLE reviews (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    like_count integer NOT NULL,
    rating numeric(2,1) NOT NULL,
    text text,
    updated_at timestamp(6) with time zone NOT NULL,
    album_id uuid NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE saved_items (
    user_id uuid NOT NULL,
    entity_type character varying(255) NOT NULL,
    entity_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT saved_items_entity_type_check CHECK (((entity_type)::text = ANY ((ARRAY['ALBUM'::character varying, 'TRACK'::character varying, 'PLAYLIST'::character varying])::text[])))
);

CREATE TABLE series (
    id uuid NOT NULL,
    cover_image_url character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    dek character varying(255),
    description text,
    like_count integer NOT NULL,
    status character varying(255) NOT NULL,
    title character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT series_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying])::text[])))
);

CREATE TABLE series_chapters (
    id uuid NOT NULL,
    audio_content_type character varying(255),
    audio_duration_ms integer,
    audio_file_size_bytes bigint,
    audio_object_key character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    note text,
    "position" integer NOT NULL,
    title character varying(255),
    type character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    series_id uuid NOT NULL,
    track_id uuid,
    CONSTRAINT series_chapters_type_check CHECK (((type)::text = ANY ((ARRAY['INTRO'::character varying, 'TRACK'::character varying, 'OUTRO'::character varying])::text[])))
);

CREATE TABLE sync_failures (
    id uuid NOT NULL,
    attempts integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    entity_type character varying(255) NOT NULL,
    last_attempt_at timestamp(6) with time zone,
    last_error text,
    payload jsonb NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT sync_failures_entity_type_check CHECK (((entity_type)::text = ANY ((ARRAY['LISTENED'::character varying, 'REVIEW_RATED'::character varying, 'REVIEW_HIGHLIGHTED'::character varying, 'TRACK_RATED'::character varying, 'PLAYLIST_TRACK_ADDED'::character varying, 'PLAYLIST_TRACK_REMOVED'::character varying, 'PLAYLIST_TRACKS_REORDERED'::character varying, 'CHAT_RECOMMENDATION_MEMORY_UPDATED'::character varying])::text[]))),
    CONSTRAINT sync_failures_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RESOLVED'::character varying, 'DEAD'::character varying])::text[])))
);

CREATE TABLE track_editorials (
    track_id uuid NOT NULL,
    editorial_id uuid NOT NULL
);

CREATE TABLE track_ratings (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    rating numeric(2,1) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    track_id uuid NOT NULL,
    user_id uuid NOT NULL
);

-- No "log_number" column — dropped before this baseline was written (see
-- Track.java; logNumber now lives only on albums, and is required there).
CREATE TABLE tracks (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    duration_ms integer,
    name character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    album_id uuid NOT NULL,
    accessibility character varying(255),
    composition_type character varying(255),
    energy character varying(255),
    image_url character varying(255),
    mood_intensity character varying(255),
    normalized_name character varying(255) NOT NULL,
    spotify_track_id character varying(255),
    spotify_url character varying(255),
    is_standout boolean NOT NULL,
    tempo_feel character varying(255),
    vocal_profile character varying(255),
    CONSTRAINT tracks_accessibility_check CHECK (((accessibility)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying])::text[]))),
    CONSTRAINT tracks_composition_type_check CHECK (((composition_type)::text = ANY ((ARRAY['ORIGINAL'::character varying, 'STANDARD'::character varying, 'COVER'::character varying, 'CONTRAFACT'::character varying, 'TRADITIONAL'::character varying])::text[]))),
    CONSTRAINT tracks_energy_check CHECK (((energy)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying])::text[]))),
    CONSTRAINT tracks_mood_intensity_check CHECK (((mood_intensity)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying])::text[]))),
    CONSTRAINT tracks_tempo_feel_check CHECK (((tempo_feel)::text = ANY ((ARRAY['BALLAD'::character varying, 'SLOW'::character varying, 'MEDIUM'::character varying, 'UP_TEMPO'::character varying, 'BURNING'::character varying])::text[]))),
    CONSTRAINT tracks_vocal_profile_check CHECK (((vocal_profile)::text = ANY ((ARRAY['INSTRUMENTAL'::character varying, 'VOCAL'::character varying])::text[])))
);

CREATE TABLE user_album_listens (
    album_id uuid NOT NULL,
    user_id uuid NOT NULL,
    listened_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE user_track_listens (
    track_id uuid NOT NULL,
    user_id uuid NOT NULL,
    listened_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE users (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    display_name character varying(255),
    email character varying(255),
    first_name character varying(255),
    last_login_at timestamp(6) with time zone,
    last_name character varying(255),
    role character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    supabase_user_id uuid NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT users_role_check CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'ADMIN'::character varying])::text[]))),
    CONSTRAINT users_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

-- Primary keys / unique constraints. album_editorials, artist_editorials and
-- track_editorials are Editorial's joined-subclass tables: their PK is the
-- shared editorial_id (same id as the parent editorials row), with a
-- separate UNIQUE on the owning-side FK (album_id/artist_id/track_id).
ALTER TABLE ONLY album_editorials ADD CONSTRAINT album_editorials_pkey PRIMARY KEY (editorial_id);
ALTER TABLE ONLY albums ADD CONSTRAINT albums_pkey PRIMARY KEY (id);
ALTER TABLE ONLY artist_editorials ADD CONSTRAINT artist_editorials_pkey PRIMARY KEY (editorial_id);
ALTER TABLE ONLY artists ADD CONSTRAINT artists_pkey PRIMARY KEY (id);
ALTER TABLE ONLY chat_exchanges ADD CONSTRAINT chat_exchanges_pkey PRIMARY KEY (id);
ALTER TABLE ONLY chat_recommendation_memory ADD CONSTRAINT chat_recommendation_memory_pkey PRIMARY KEY (id);
ALTER TABLE ONLY chats ADD CONSTRAINT chats_pkey PRIMARY KEY (id);
ALTER TABLE ONLY editorial_blocks ADD CONSTRAINT editorial_blocks_pkey PRIMARY KEY (id);
ALTER TABLE ONLY editorials ADD CONSTRAINT editorials_pkey PRIMARY KEY (id);
ALTER TABLE ONLY likes ADD CONSTRAINT likes_pkey PRIMARY KEY (user_id, entity_type, entity_id);
ALTER TABLE ONLY listens ADD CONSTRAINT listens_pkey PRIMARY KEY (user_id, entity_type, entity_id);
ALTER TABLE ONLY notes ADD CONSTRAINT notes_pkey PRIMARY KEY (id);
ALTER TABLE ONLY playlist_tracks ADD CONSTRAINT playlist_tracks_pkey PRIMARY KEY (id);
ALTER TABLE ONLY playlists ADD CONSTRAINT playlists_pkey PRIMARY KEY (id);
ALTER TABLE ONLY review_standout_tracks ADD CONSTRAINT review_standout_tracks_pkey PRIMARY KEY (review_id, track_id);
ALTER TABLE ONLY reviews ADD CONSTRAINT reviews_pkey PRIMARY KEY (id);
ALTER TABLE ONLY saved_items ADD CONSTRAINT saved_items_pkey PRIMARY KEY (user_id, entity_type, entity_id);
ALTER TABLE ONLY series_chapters ADD CONSTRAINT series_chapters_pkey PRIMARY KEY (id);
ALTER TABLE ONLY series ADD CONSTRAINT series_pkey PRIMARY KEY (id);
ALTER TABLE ONLY sync_failures ADD CONSTRAINT sync_failures_pkey PRIMARY KEY (id);
ALTER TABLE ONLY track_editorials ADD CONSTRAINT track_editorials_pkey PRIMARY KEY (editorial_id);
ALTER TABLE ONLY track_ratings ADD CONSTRAINT track_ratings_pkey PRIMARY KEY (id);
ALTER TABLE ONLY tracks ADD CONSTRAINT tracks_pkey PRIMARY KEY (id);
ALTER TABLE ONLY chat_recommendation_memory ADD CONSTRAINT uk10lr5x7p8mn22vmrbg1tkb27r UNIQUE (chat_id);
ALTER TABLE ONLY track_editorials ADD CONSTRAINT uk7deg1jpxg9do475r7h5iwhq5e UNIQUE (track_id);
ALTER TABLE ONLY playlists ADD CONSTRAINT ukbq229u3bbkas0bmcvn265p3b2 UNIQUE (slug);
ALTER TABLE ONLY artist_editorials ADD CONSTRAINT ukcw04wpcnm1yhu75qou05b5fkw UNIQUE (artist_id);
ALTER TABLE ONLY album_editorials ADD CONSTRAINT uksjyu6p0vi7p97hnp91mjwcv4w UNIQUE (album_id);
ALTER TABLE ONLY users ADD CONSTRAINT uktamcja6eov3opw7ij5pm3o3v7 UNIQUE (supabase_user_id);
ALTER TABLE ONLY playlist_tracks ADD CONSTRAINT uq_playlist_tracks_playlist_track UNIQUE (playlist_id, track_id);
ALTER TABLE ONLY reviews ADD CONSTRAINT uq_reviews_user_album UNIQUE (user_id, album_id);
ALTER TABLE ONLY series_chapters ADD CONSTRAINT uq_series_chapters_series_position UNIQUE (series_id, "position");
ALTER TABLE ONLY track_ratings ADD CONSTRAINT uq_track_ratings_user_track UNIQUE (user_id, track_id);
ALTER TABLE ONLY user_album_listens ADD CONSTRAINT user_album_listens_pkey PRIMARY KEY (album_id, user_id);
ALTER TABLE ONLY user_track_listens ADD CONSTRAINT user_track_listens_pkey PRIMARY KEY (track_id, user_id);
ALTER TABLE ONLY users ADD CONSTRAINT users_pkey PRIMARY KEY (id);

-- Foreign keys.
ALTER TABLE ONLY notes ADD CONSTRAINT fk2ygglx4i0kdw4xyyae4ss93pg FOREIGN KEY (track_id) REFERENCES tracks(id);
ALTER TABLE ONLY editorial_blocks ADD CONSTRAINT fk3iwgtebvh6962ofuy8fo49edf FOREIGN KEY (editorial_id) REFERENCES editorials(id);
ALTER TABLE ONLY review_standout_tracks ADD CONSTRAINT fk60orxrp9q09krvy671qanugga FOREIGN KEY (review_id) REFERENCES reviews(id);
ALTER TABLE ONLY chat_exchanges ADD CONSTRAINT fk69353pcli31utppsbt6qd18ur FOREIGN KEY (chat_id) REFERENCES chats(id);
ALTER TABLE ONLY album_editorials ADD CONSTRAINT fk6tftiw45d8jb2y72b920ic24 FOREIGN KEY (album_id) REFERENCES albums(id);
ALTER TABLE ONLY albums ADD CONSTRAINT fk72gqyi6l1j674radjyitcm86f FOREIGN KEY (artist_id) REFERENCES artists(id);
ALTER TABLE ONLY track_editorials ADD CONSTRAINT fk7vgmwjp1nmmrq26b9kajktfye FOREIGN KEY (editorial_id) REFERENCES editorials(id);
ALTER TABLE ONLY track_ratings ADD CONSTRAINT fkbov7xfyxyq5i5u1hujecaab1e FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY track_ratings ADD CONSTRAINT fkc91c51sfvni3610h0aoac5mqm FOREIGN KEY (track_id) REFERENCES tracks(id);
ALTER TABLE ONLY reviews ADD CONSTRAINT fkcgy7qjc1r99dp117y9en6lxye FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY tracks ADD CONSTRAINT fkdcmijveo7n1lql01vav1u2jd2 FOREIGN KEY (album_id) REFERENCES albums(id);
ALTER TABLE ONLY notes ADD CONSTRAINT fkechaouoa6kus6k1dpix1u91c FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY series_chapters ADD CONSTRAINT fkgs3b69knl92ks2ro104rw5qf0 FOREIGN KEY (series_id) REFERENCES series(id);
ALTER TABLE ONLY track_editorials ADD CONSTRAINT fkhhqjadfjc5xv3c97qpf8h9tbj FOREIGN KEY (track_id) REFERENCES tracks(id);
ALTER TABLE ONLY playlist_tracks ADD CONSTRAINT fkhjkawu4qwjhxcpveah0pymuct FOREIGN KEY (track_id) REFERENCES tracks(id);
ALTER TABLE ONLY artist_editorials ADD CONSTRAINT fkicfxls7gckni6840ml17jcpja FOREIGN KEY (artist_id) REFERENCES artists(id);
ALTER TABLE ONLY album_editorials ADD CONSTRAINT fkj4vf7ujbh3gvuexrdgoitig3q FOREIGN KEY (editorial_id) REFERENCES editorials(id);
ALTER TABLE ONLY artist_editorials ADD CONSTRAINT fkjo7ktkn66ykuq7g6tw1fn21p FOREIGN KEY (editorial_id) REFERENCES editorials(id);
ALTER TABLE ONLY reviews ADD CONSTRAINT fkk4e0mc7mj20wk24tyopt4msk0 FOREIGN KEY (album_id) REFERENCES albums(id);
ALTER TABLE ONLY chats ADD CONSTRAINT fkmolqi1xj49bg3jjr33674limy FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY review_standout_tracks ADD CONSTRAINT fkn15dbkioy7qdok5jaq8lha8iw FOREIGN KEY (track_id) REFERENCES tracks(id);
ALTER TABLE ONLY playlist_tracks ADD CONSTRAINT fkn9g4py06v2tmrisjdvxvjeb7x FOREIGN KEY (playlist_id) REFERENCES playlists(id);
ALTER TABLE ONLY series_chapters ADD CONSTRAINT fktbmi2pfs26n2jop0pwm2ds0ef FOREIGN KEY (track_id) REFERENCES tracks(id);
