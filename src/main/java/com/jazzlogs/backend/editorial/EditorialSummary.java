package com.jazzlogs.backend.editorial;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Read-only mapping over the `editorial_summaries` view (see
// V9__editorial_summaries_view.sql) — the UNION across Album/Track/
// ArtistEditorial that lets the archive endpoints filter, search and page
// across all three owner types with a single JPQL query, letting Spring
// Data translate Pageable's Sort automatically.
@Entity
@Immutable
@Table(name = "editorial_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EditorialSummary {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type")
    private EditorialOwnerType ownerType;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "owner_image_url")
    private String ownerImageUrl;

    private String title;

    private String dek;

    private String byline;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "like_count")
    private int likeCount;

    private boolean featurated;

    // Artist name for an album, album name for a track — null for an artist
    // editorial (nothing one hop further to show).
    @Column(name = "context_name")
    private String contextName;

    @Column(name = "release_year")
    private Integer releaseYear;

    // First block's raw text, for the archive lead card's preview snippet —
    // no embedding, no other block metadata (see V11's LATERAL join).
    @Column(name = "preview_text")
    private String previewText;

    // Artist id for an album, album id for a track — null for an artist. Lets
    // the archive deep-link a track editorial straight into its album's
    // editorial page instead of nowhere (see V13).
    @Column(name = "context_id")
    private UUID contextId;
}
