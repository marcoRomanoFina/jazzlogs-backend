package com.jazzlogs.backend.album;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.album.dto.AlbumDetailDto;
import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.CreateAlbumRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.album.dto.PersonnelRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.editorial.AlbumEditorial;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialDto;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialRequest;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.review.ReviewService;
import com.jazzlogs.backend.review.dto.AlbumRatingStats;
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

    private final AlbumService albumService;
    private final TrackService trackService;
    private final EditorialService editorialService;
    private final ListenService listenService;
    private final ReviewService reviewService;
    private final UserService userService;

    @GetMapping("/{id}")
    public AlbumDetailDto getAlbum(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return albumService.getAlbumDetail(id, currentUserId(jwt));
    }

    // Upserts by spotifyAlbumId — posting the same album again updates it in
    // place instead of creating a duplicate. See AlbumService.createOrUpdateAlbum.
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AlbumDetailDto> createOrUpdateAlbum(@Valid @RequestBody CreateAlbumRequest request, @AuthenticationPrincipal Jwt jwt) {
        Album album = albumService.createOrUpdateAlbum(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(albumService.getAlbumDetail(album.getId(), currentUserId(jwt)));
    }

    // Upserts by spotifyTrackId — posting the same track again updates it in
    // place instead of creating a duplicate. See TrackService.createOrUpdateTrack.
    @PostMapping("/{id}/tracks")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TrackDto> createOrUpdateTrack(@PathVariable UUID id, @Valid @RequestBody CreateTrackRequest request) {
        Track track = trackService.createOrUpdateTrack(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(trackService.toDto(track));
    }

    @PostMapping("/{id}/editorial")
    @PreAuthorize("hasRole('ADMIN')")
    public AlbumEditorialDto upsertEditorial(@PathVariable UUID id, @Valid @RequestBody AlbumEditorialRequest request, @AuthenticationPrincipal Jwt jwt) {
        AlbumEditorial editorial = editorialService.upsertAlbumEditorial(id, request);
        return editorialService.toDto(editorial, currentUserId(jwt));
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

    @PostMapping("/{id}/tags/style")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addStyle(@PathVariable UUID id, @RequestBody StyleTagRequest request) {
        albumService.addStyle(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/tags/mood")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addMood(@PathVariable UUID id, @RequestBody MoodTagRequest request) {
        albumService.addMood(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/tags/context")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addContext(@PathVariable UUID id, @RequestBody ContextTagRequest request) {
        albumService.addContext(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/listen")
    public ResponseEntity<Void> markListened(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        listenService.markAlbumListened(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reviews")
    public ReviewDto upsertReview(@PathVariable UUID id, @Valid @RequestBody CreateReviewRequest request, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.upsertReview(currentUserId(jwt), id, request.rating(), request.text(), request.standoutTrackIds());
    }

    @DeleteMapping("/{id}/reviews")
    public ResponseEntity<Void> deleteReview(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        reviewService.deleteReview(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/reviews")
    public List<ReviewDto> getAlbumReviews(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.getAlbumReviews(id, currentUserId(jwt));
    }

    @GetMapping("/{id}/reviews/me")
    public ReviewDto getMyReview(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.getMyReview(id, currentUserId(jwt));
    }

    @GetMapping("/{id}/rating")
    public AlbumRatingStats getAlbumRating(@PathVariable UUID id) {
        return reviewService.getAlbumRatingStats(id);
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
