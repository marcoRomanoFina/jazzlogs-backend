package com.jazzlogs.backend.playlist;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.playlist.dto.PlaylistDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistIdDto;
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
        User user = userService.resolveFromJwt(jwt);
        return playlistService.list(user.getRole() == UserRole.ADMIN, user.getId());
    }

    @GetMapping("/{id}")
    public PlaylistDetailDto getDetail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveFromJwt(jwt);
        return playlistService.getPlaylistDetail(id, user.getId(), user.getRole() == UserRole.ADMIN);
    }

    /** The featured playlist — see {@link PlaylistService#getFeatured}. */
    @GetMapping("/featured")
    public PlaylistSummaryDto getFeatured(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveFromJwt(jwt);
        return playlistService.getFeatured(user.getId(), user.getRole() == UserRole.ADMIN)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No playlist is featured"));
    }

    /** The most recently published JOURNEY playlist — see {@link PlaylistService#getJourney}. */
    @GetMapping("/journey")
    public PlaylistSummaryDto getJourney(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveFromJwt(jwt);
        return playlistService.getJourney(user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No journey playlist has been published"));
    }

    /** The playlist "Catalogue", JOURNEY only — see {@link PlaylistService#getCatalogue}. */
    @GetMapping("/journeys")
    public Page<PlaylistSummaryDto> listJourneys(
        @PageableDefault(size = 12, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
        @AuthenticationPrincipal Jwt jwt
    ) {
        User user = userService.resolveFromJwt(jwt);
        return playlistService.getCatalogue(PlaylistType.JOURNEY, user.getRole() == UserRole.ADMIN, user.getId(), pageable);
    }

    /** The playlist "Catalogue", STANDARD only — see {@link PlaylistService#getCatalogue}. */
    @GetMapping("/standard")
    public Page<PlaylistSummaryDto> listStandard(
        @PageableDefault(size = 12, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
        @AuthenticationPrincipal Jwt jwt
    ) {
        User user = userService.resolveFromJwt(jwt);
        return playlistService.getCatalogue(PlaylistType.STANDARD, user.getRole() == UserRole.ADMIN, user.getId(), pageable);
    }

    /** The playlist "Catalogue", every type mixed together — see {@link PlaylistService#getCatalogue(boolean, UUID, Pageable)}. */
    @GetMapping("/catalogue")
    public Page<PlaylistSummaryDto> listCatalogue(
        @PageableDefault(size = 12, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
        @AuthenticationPrincipal Jwt jwt
    ) {
        User user = userService.resolveFromJwt(jwt);
        return playlistService.getCatalogue(user.getRole() == UserRole.ADMIN, user.getId(), pageable);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PlaylistIdDto> create(@Valid @RequestBody PlaylistUpsertRequest request) {
        Playlist playlist = playlistService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PlaylistIdDto(playlist.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PlaylistDetailDto update(@PathVariable UUID id, @Valid @RequestBody PlaylistUpsertRequest request) {
        return playlistService.update(id, request);
    }

    /** Hard-deletes this playlist and every trace of it — see {@link PlaylistService#delete}. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        playlistService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Publishes this playlist — see {@link PlaylistService#publish}. */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> publish(@PathVariable UUID id) {
        playlistService.publish(id);
        return ResponseEntity.noContent().build();
    }

    /** Reverts this playlist to draft — see {@link PlaylistService#unpublish}. */
    @DeleteMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unpublish(@PathVariable UUID id) {
        playlistService.unpublish(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads a new cover image for this playlist — see {@link PlaylistService#setCoverImage}.
     *
     * @param id   the playlist
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setCoverImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        playlistService.setCoverImage(id, file);
        return ResponseEntity.noContent().build();
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
    public PlaylistTrackDetailDto updateTrackNote(@PathVariable UUID id, @PathVariable UUID trackId, @Valid @RequestBody UpdateTrackNoteRequest request) {
        return playlistService.updateTrackNote(id, trackId, request.title(), request.curatorNote());
    }

    @PutMapping("/{id}/tracks/reorder")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> reorderTracks(@PathVariable UUID id, @Valid @RequestBody ReorderPlaylistTracksRequest request) {
        playlistService.reorderTracks(id, request.trackIds());
        return ResponseEntity.noContent().build();
    }

    /** Marks this playlist as THE featured one — see {@link PlaylistService#setFeatured}. */
    @PostMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFeatured(@PathVariable UUID id) {
        playlistService.setFeatured(id);
        return ResponseEntity.noContent().build();
    }

    /** Removes this playlist from being featured — see {@link PlaylistService#unsetFeatured}. */
    @DeleteMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unsetFeatured(@PathVariable UUID id) {
        playlistService.unsetFeatured(id);
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

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
