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

import com.jazzlogs.backend.album.Album;

@Entity
@Table(name = "album_editorials")
@PrimaryKeyJoinColumn(name = "editorial_id")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlbumEditorial extends Editorial {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id", nullable = false, unique = true)
    private Album album;

    // Four images for this editorial's own page layout — upload-only, never
    // set via Editorial.update(...), only via their own endpoints (see
    // EditorialService.setAlbumEditorial*Image).
    @Column(name = "principal_image_url")
    private String principalImageUrl;

    @Column(name = "secondary_image_url")
    private String secondaryImageUrl;

    @Column(name = "banner_image_url")
    private String bannerImageUrl;

    @Column(name = "footer_image_url")
    private String footerImageUrl;

    public AlbumEditorial(Album album) {
        this.album = album;
    }

    public void updatePrincipalImageUrl(String principalImageUrl) {
        this.principalImageUrl = principalImageUrl;
    }

    public void updateSecondaryImageUrl(String secondaryImageUrl) {
        this.secondaryImageUrl = secondaryImageUrl;
    }

    public void updateBannerImageUrl(String bannerImageUrl) {
        this.bannerImageUrl = bannerImageUrl;
    }

    public void updateFooterImageUrl(String footerImageUrl) {
        this.footerImageUrl = footerImageUrl;
    }
}
