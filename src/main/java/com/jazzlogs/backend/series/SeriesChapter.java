package com.jazzlogs.backend.series;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.jazzlogs.backend.track.Track;

// One row per (series, position) — DB-enforced via uq_series_chapters_series_position.
// track_id is a real FK (unlike likes/listens' polymorphic entity_id) — a chapter
// points at exactly one concrete Track. The DB CHECK constraint mirrors

@Entity
@Table(name = "series_chapters", uniqueConstraints = @UniqueConstraint(name = "uq_series_chapters_series_position", columnNames = {"series_id", "position"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeriesChapter {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id", nullable = false)
    private Series series;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChapterType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id")
    private Track track;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "audio_object_key")
    private String audioObjectKey;

    @Column(name = "audio_duration_ms")
    private Integer audioDurationMs;

    @Column(name = "audio_content_type")
    private String audioContentType;

    @Column(name = "audio_file_size_bytes")
    private Long audioFileSizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SeriesChapter(
        Series series,
        int position,
        ChapterType type,
        Track track,
        String title,
        String note,
        String audioObjectKey,
        Integer audioDurationMs,
        String audioContentType,
        Long audioFileSizeBytes
    ) {
        this.series = series;
        this.position = position;
        this.type = type;
        this.track = track;
        this.title = title;
        this.note = note;
        this.audioObjectKey = audioObjectKey;
        this.audioDurationMs = audioDurationMs;
        this.audioContentType = audioContentType;
        this.audioFileSizeBytes = audioFileSizeBytes;
    }

    public void updatePosition(int position) {
        this.position = position;
    }

    public void updateDetails(
        ChapterType type,
        Track track,
        String title,
        String note,
        String audioObjectKey,
        Integer audioDurationMs,
        String audioContentType,
        Long audioFileSizeBytes
    ) {
        this.type = type;
        this.track = track;
        this.title = title;
        this.note = note;
        this.audioObjectKey = audioObjectKey;
        this.audioDurationMs = audioDurationMs;
        this.audioContentType = audioContentType;
        this.audioFileSizeBytes = audioFileSizeBytes;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
