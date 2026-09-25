package com.jazzlogs.backend.album;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.album.dto.AlbumHeaderDto;
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

    // No POST/DELETE /{id}/listen anymore — an album's "listened" state
    // isn't something a user sets directly, it's a consequence of listening
    // to every one of its tracks (POST/DELETE /tracks/{id}/listen), computed
    // in AlbumService.getAlbumHeader and reconciled by
    // ListenService.syncAlbumCompletionState.

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
