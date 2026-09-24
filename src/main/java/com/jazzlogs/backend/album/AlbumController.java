package com.jazzlogs.backend.album;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.album.dto.AlbumHeaderDto;
import com.jazzlogs.backend.album.dto.ContextTagRequest;
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
     * The album's minimal support metadata — see {@link AlbumService#getAlbumHeader}.
     *
     * @param id the album to load
     * @return the album header
     */
    @GetMapping("/{id}")
    public AlbumHeaderDto getAlbum(@PathVariable UUID id) {
        return albumService.getAlbumHeader(id);
    }

    /** The album page's track list, fetched separately from the header — see {@link AlbumService#getAlbumTracks}. */
    @GetMapping("/{id}/tracks")
    public List<TrackDto> getAlbumTracks(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return albumService.getAlbumTracks(id, currentUserId(jwt));
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

    // No POST/DELETE /{id}/listen anymore — an album's "listened" state
    // isn't something a user sets directly, it's a consequence of listening
    // to every one of its tracks (POST/DELETE /tracks/{id}/listen), computed
    // in AlbumService.getAlbumHeader and reconciled by
    // ListenService.syncAlbumCompletionState.

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
