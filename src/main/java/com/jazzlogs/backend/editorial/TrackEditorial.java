package com.jazzlogs.backend.editorial;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.jazzlogs.backend.track.Track;

@Entity
@Table(name = "track_editorials")
@PrimaryKeyJoinColumn(name = "editorial_id")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrackEditorial extends Editorial {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id", nullable = false, unique = true)
    private Track track;

    // Upload-only, never set via Editorial.update(...) — only via its own
    // endpoint (see EditorialService.setTrackEditorialImage).
    @Column(name = "image_url")
    private String imageUrl;

    public TrackEditorial(Track track) {
        this.track = track;
    }

    public void updateImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
