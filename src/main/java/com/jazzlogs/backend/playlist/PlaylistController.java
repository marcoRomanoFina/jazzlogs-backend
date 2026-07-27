package com.jazzlogs.backend.playlist;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.playlist.dto.PlaylistDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistSummaryDto;
import com.jazzlogs.backend.playlist.dto.PlaylistTrackDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistTrackInput;
import com.jazzlogs.backend.playlist.dto.PlaylistUpsertRequest;
import com.jazzlogs.backend.playlist.dto.ReorderPlaylistTracksRequest;
import com.jazzlogs.backend.playlist.dto.UpdateTrackNoteRequest;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRole;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

// Like/unlike PLAYLIST reuses the already-generic /likes endpoints (LikeService
// now has PlaylistRepository wired into its map) — no like/unlike endpoints here.
@RestController
@RequestMapping("/playlists")
@AllArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;
    private final ListenService listenService;
    private final UserService userService;

    // Non-admins only see published playlists; admins see everything.
    @GetMapping
    public List<PlaylistSummaryDto> list(@AuthenticationPrincipal Jwt jwt) {
        return playlistService.list(isAdmin(jwt));
    }

    @GetMapping("/{id}")
    public PlaylistDetailDto getDetail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveFromJwt(jwt);
        return playlistService.getPlaylistDetail(id, user.getId(), user.getRole() == UserRole.ADMIN);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PlaylistDetailDto create(@Valid @RequestBody PlaylistUpsertRequest request) {
        return playlistService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PlaylistDetailDto update(@PathVariable UUID id, @Valid @RequestBody PlaylistUpsertRequest request) {
        return playlistService.update(id, request);
    }

    @PostMapping("/{id}/tracks")
    @PreAuthorize("hasRole('ADMIN')")
    public PlaylistTrackDetailDto addTrack(@PathVariable UUID id, @Valid @RequestBody PlaylistTrackInput request) {
        return playlistService.addTrack(id, request.trackId(), request.title(), request.curatorNote());
    }

    @DeleteMapping("/{id}/tracks/{trackId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removeTrack(@PathVariable UUID id, @PathVariable UUID trackId) {
        playlistService.removeTrack(id, trackId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/tracks/{trackId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PlaylistTrackDetailDto updateTrackNote(@PathVariable UUID id, @PathVariable UUID trackId, @RequestBody UpdateTrackNoteRequest request) {
        return playlistService.updateTrackNote(id, trackId, request.title(), request.curatorNote());
    }

    @PutMapping("/{id}/tracks/reorder")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> reorderTracks(@PathVariable UUID id, @Valid @RequestBody ReorderPlaylistTracksRequest request) {
        playlistService.reorderTracks(id, request.trackIds());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/listened")
    public ResponseEntity<Void> markListened(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        listenService.markPlaylistListened(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/listened")
    public ResponseEntity<Void> unmarkListened(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        listenService.unmarkPlaylistListened(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private boolean isAdmin(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getRole() == UserRole.ADMIN;
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
