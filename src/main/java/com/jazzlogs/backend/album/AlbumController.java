package com.jazzlogs.backend.album;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.album.dto.AlbumHeaderDto;
import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.CreateAlbumRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.album.dto.PersonnelRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.editorial.AlbumEditorial;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialDto;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialRequest;
import com.jazzlogs.backend.review.ReviewService;
import com.jazzlogs.backend.review.dto.CreateReviewRequest;
import com.jazzlogs.backend.review.dto.ReviewDto;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackService;
import com.jazzlogs.backend.track.dto.CreateTrackRequest;
import com.jazzlogs.backend.track.dto.TrackDto;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/albums")
@AllArgsConstructor
public class AlbumController {

    /** Fixed server-side, not a client-controlled ?size — see {@link #getAlbumReviews}. */
    private static final int REVIEWS_PAGE_SIZE = 6;

    private final AlbumService albumService;
    private final TrackService trackService;
    private final EditorialService editorialService;
    private final ReviewService reviewService;
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

    // Upserts by spotifyAlbumId — posting the same album again updates it in
    // place instead of creating a duplicate. See AlbumService.createOrUpdateAlbum.
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AlbumHeaderDto> createOrUpdateAlbum(@Valid @RequestBody CreateAlbumRequest request, @AuthenticationPrincipal Jwt jwt) {
        Album album = albumService.createOrUpdateAlbum(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(albumService.getAlbumHeader(album.getId(), currentUserId(jwt)));
    }

    // Upserts by spotifyTrackId — posting the same track again updates it in
    // place instead of creating a duplicate. See TrackService.createOrUpdateTrack.
    @PostMapping("/{id}/tracks")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TrackDto> createOrUpdateTrack(@PathVariable UUID id, @Valid @RequestBody CreateTrackRequest request) {
        Track track = trackService.createOrUpdateTrack(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(trackService.toTrackDto(track));
    }

    @PostMapping("/{id}/editorial")
    @PreAuthorize("hasRole('ADMIN')")
    public AlbumEditorialDto upsertEditorial(@PathVariable UUID id, @Valid @RequestBody AlbumEditorialRequest request, @AuthenticationPrincipal Jwt jwt) {
        AlbumEditorial editorial = editorialService.upsertAlbumEditorial(id, request);
        return editorialService.toAlbumEditorialDto(editorial, currentUserId(jwt));
    }

    @PostMapping("/{id}/personnel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addPersonnel(@PathVariable UUID id, @RequestBody PersonnelRequest request) {
        albumService.addPersonnel(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/entry-point/{artistId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> markEntryPoint(@PathVariable UUID id, @PathVariable UUID artistId) {
        albumService.markEntryPoint(id, artistId);
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

    // No POST/DELETE /{id}/listen anymore — an album's "listened" state
    // isn't something a user sets directly, it's a consequence of listening
    // to every one of its tracks (POST/DELETE /tracks/{id}/listen), computed
    // in AlbumService.getAlbumHeader and reconciled by
    // ListenService.syncAlbumCompletionState.

    /**
     * Creates the caller's own review of this album — see {@link ReviewService#createReview}.
     *
     * @param id      the album being reviewed
     * @param request the review's own fields
     * @param jwt     the caller
     * @return the created review
     */
    @PostMapping("/{id}/reviews")
    public ReviewDto createReview(@PathVariable UUID id, @Valid @RequestBody CreateReviewRequest request, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.createReview(currentUserId(jwt), id, request.rating(), request.text(), request.standoutTrackIds());
    }

    /**
     * Edits the caller's own existing review of this album — see {@link ReviewService#updateReview}.
     *
     * @param id      the album being reviewed
     * @param request the review's own fields — a full replace, not a partial patch
     * @param jwt     the caller
     * @return the updated review
     */
    @PutMapping("/{id}/reviews")
    public ReviewDto updateReview(@PathVariable UUID id, @Valid @RequestBody CreateReviewRequest request, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.updateReview(currentUserId(jwt), id, request.rating(), request.text(), request.standoutTrackIds());
    }

    /**
     * Deletes the caller's own review of this album — see {@link ReviewService#deleteReview}.
     *
     * @param id  the album being reviewed
     * @param jwt the caller
     */
    @DeleteMapping("/{id}/reviews")
    public ResponseEntity<Void> deleteReview(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        reviewService.deleteReview(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The album's reviews, paginated — the caller's own review (if any)
     * always leads, then everyone else's newest first. Same shape as
     * {@code TrackController#getTrackNotes}; the frontend uses page 0's
     * first item (if its {@code userId} matches the caller) to know
     * whether to {@link #createReview} or {@link #updateReview} — there's
     * no separate "my review" endpoint anymore.
     *
     * @param id   the album
     * @param jwt  the caller, resolved to a user id for "mine first" and each review's {@code likedByCurrentUser}
     * @param page 0-based; page size is fixed at {@link #REVIEWS_PAGE_SIZE}, not client-controlled
     * @return the matching page
     */
    @GetMapping("/{id}/reviews")
    public Page<ReviewDto> getAlbumReviews(
        @PathVariable UUID id,
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") int page
    ) {
        return reviewService.getAlbumReviews(id, currentUserId(jwt), PageRequest.of(page, REVIEWS_PAGE_SIZE));
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
