package com.jazzlogs.backend.album;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.album.dto.AlbumHeaderDto;
import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.CoverColorRequest;
import com.jazzlogs.backend.album.dto.LetterColorRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.track.dto.TrackDto;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/albums")
@AllArgsConstructor
public class AlbumController {

    private final AlbumService albumService;
    private final UserService userService;

    /**
     * The album page's fast, above-the-fold load — see {@link AlbumService#getAlbumHeader}.
     *
     * @param id  the album to load
     * @param jwt the caller, resolved to a user id only to compute listen/save state
     * @return the album header
     */
    @GetMapping("/{id}")
    public AlbumHeaderDto getAlbum(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return albumService.getAlbumHeader(id, currentUserId(jwt));
    }

    /** The album page's track list, fetched separately from the header — see {@link AlbumService#getAlbumTracks}. */
    @GetMapping("/{id}/tracks")
    public List<TrackDto> getAlbumTracks(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return albumService.getAlbumTracks(id, currentUserId(jwt));
    }

    /**
     * Marks this album as a good entry point into an artist — see {@link AlbumService#markEntryPoint}.
     *
     * @param id       the album
     * @param artistId the artist this album is a good entry point into
     */
    @PostMapping("/{id}/entry-point/{artistId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> markEntryPoint(@PathVariable UUID id, @PathVariable UUID artistId) {
        albumService.markEntryPoint(id, artistId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Unmarks this album as a good entry point into an artist — see {@link AlbumService#unmarkEntryPoint}.
     *
     * @param id       the album
     * @param artistId the artist
     */
    @DeleteMapping("/{id}/entry-point/{artistId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unmarkEntryPoint(@PathVariable UUID id, @PathVariable UUID artistId) {
        albumService.unmarkEntryPoint(id, artistId);
        return ResponseEntity.noContent().build();
    }

    // Full replace, not add-one — see StyleTagRequest's comment.
    @PutMapping("/{id}/tags/style")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceStyles(@PathVariable UUID id, @RequestBody StyleTagRequest request) {
        albumService.replaceStyles(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/tags/mood")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceMoods(@PathVariable UUID id, @RequestBody MoodTagRequest request) {
        albumService.replaceMoods(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/tags/context")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceContexts(@PathVariable UUID id, @RequestBody ContextTagRequest request) {
        albumService.replaceContexts(id, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sets the album's curated cover color — see {@link AlbumService#setCoverColor}.
     *
     * @param id      the album
     * @param request the color to set
     */
    @PutMapping("/{id}/cover-color")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setCoverColor(@PathVariable UUID id, @Valid @RequestBody CoverColorRequest request) {
        albumService.setCoverColor(id, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Clears the album's curated cover color — see {@link AlbumService#clearCoverColor}.
     *
     * @param id the album
     */
    @DeleteMapping("/{id}/cover-color")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> clearCoverColor(@PathVariable UUID id) {
        albumService.clearCoverColor(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sets the album's curated letter (text) color — see {@link AlbumService#setLetterColor}.
     *
     * @param id      the album
     * @param request the color to set
     */
    @PutMapping("/{id}/letter-color")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setLetterColor(@PathVariable UUID id, @Valid @RequestBody LetterColorRequest request) {
        albumService.setLetterColor(id, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Clears the album's curated letter color — see {@link AlbumService#clearLetterColor}.
     *
     * @param id the album
     */
    @DeleteMapping("/{id}/letter-color")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> clearLetterColor(@PathVariable UUID id) {
        albumService.clearLetterColor(id);
        return ResponseEntity.noContent().build();
    }

    /** Marks this album as THE featured album — see {@link AlbumService#setFeatured}. */
    @PostMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFeatured(@PathVariable UUID id) {
        albumService.setFeatured(id);
        return ResponseEntity.noContent().build();
    }

    /** Removes this album from being featured — see {@link AlbumService#unsetFeatured}. */
    @DeleteMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unsetFeatured(@PathVariable UUID id) {
        albumService.unsetFeatured(id);
        return ResponseEntity.noContent().build();
    }

    // No POST/DELETE /{id}/listen anymore — an album's "listened" state
    // isn't something a user sets directly, it's a consequence of listening
    // to every one of its tracks (POST/DELETE /tracks/{id}/listen), computed
    // in AlbumService.getAlbumHeader and reconciled by
    // ListenService.syncAlbumCompletionState.

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
