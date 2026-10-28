package com.jazzlogs.backend.spotify;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/spotify")
@RequiredArgsConstructor
public class SpotifyAdminController {

    private final SpotifyCatalogService spotifyCatalogService;

    /**
     * Preview only — doesn't create anything. Lets the frontend confirm "is this
     * the right album?" before POST /albums, so a mistyped spotifyAlbumId doesn't
     * end up creating an Album with the wrong catalog data.
     */
    @GetMapping("/album/{spotifyAlbumId}")
    @PreAuthorize("hasRole('ADMIN')")
    public SpotifyAlbumData previewAlbum(@PathVariable String spotifyAlbumId) {
        return spotifyCatalogService.fetchAlbum(spotifyAlbumId);
    }
}
