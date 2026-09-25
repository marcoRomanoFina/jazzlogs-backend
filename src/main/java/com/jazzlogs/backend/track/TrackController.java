package com.jazzlogs.backend.track;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.TrackEditorial;
import com.jazzlogs.backend.editorial.dto.FeaturedTrackDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.note.NoteService;
import com.jazzlogs.backend.note.dto.CreateNoteRequest;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.track.dto.CreateTrackRequest;
import com.jazzlogs.backend.track.dto.FeaturedInstrumentsRequest;
import com.jazzlogs.backend.track.dto.PerformerRequest;
import com.jazzlogs.backend.track.dto.RhythmTagRequest;
import com.jazzlogs.backend.track.dto.TrackDto;
import com.jazzlogs.backend.track.dto.TrackTagsDto;
import com.jazzlogs.backend.trackrating.TrackRatingService;
import com.jazzlogs.backend.trackrating.dto.CreateTrackRatingRequest;
import com.jazzlogs.backend.trackrating.dto.TrackRatingDto;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/tracks")
@AllArgsConstructor
public class TrackController {

    /** Fixed server-side, not a client-controlled ?size — see {@link #getTrackNotes}. */
    private static final int NOTES_PAGE_SIZE = 6;

    private final TrackService trackService;
    private final EditorialService editorialService;
    private final ListenService listenService;
    private final NoteService noteService;
    private final TrackRatingService trackRatingService;
    private final UserService userService;

    // Upserts by spotifyTrackId — posting the same track again updates it in
    // place instead of creating a duplicate. Track-first: Album/Artist are
    // resolved or created automatically from the track's own Spotify data
    // (see TrackService.createOrUpdateTrack), no separate album/artist step.
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TrackDto> createOrUpdateTrack(@Valid @RequestBody CreateTrackRequest request) {
        Track track = trackService.createOrUpdateTrack(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(trackService.toTrackDto(track));
    }

    @PostMapping("/{id}/editorial")
    @PreAuthorize("hasRole('ADMIN')")
    public TrackEditorialDto upsertEditorial(@PathVariable UUID id, @Valid @RequestBody TrackEditorialRequest request) {
        TrackEditorial editorial = editorialService.upsertTrackEditorial(id, request);
        return editorialService.toTrackEditorialDto(editorial);
    }

    /**
     * Uploads this track's editorial's cover image — see {@link EditorialService#setTrackEditorialCoverImage}.
     *
     * @param id   the track
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/editorial/cover-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setEditorialCoverImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        editorialService.setTrackEditorialCoverImage(id, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads this track's editorial's principal image — see {@link EditorialService#setTrackEditorialPrincipalImage}.
     *
     * @param id   the track
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/editorial/principal-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setEditorialPrincipalImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        editorialService.setTrackEditorialPrincipalImage(id, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads this track's editorial's secondary image — see {@link EditorialService#setTrackEditorialSecondaryImage}.
     *
     * @param id   the track
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/editorial/secondary-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setEditorialSecondaryImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        editorialService.setTrackEditorialSecondaryImage(id, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads this track's editorial's banner image — see {@link EditorialService#setTrackEditorialBannerImage}.
     *
     * @param id   the track
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/editorial/banner-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setEditorialBannerImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        editorialService.setTrackEditorialBannerImage(id, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads this track's editorial's footer image — see {@link EditorialService#setTrackEditorialFooterImage}.
     *
     * @param id   the track
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/editorial/footer-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setEditorialFooterImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        editorialService.setTrackEditorialFooterImage(id, file);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/performers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addPerformer(@PathVariable UUID id, @RequestBody PerformerRequest request) {
        trackService.addPerformer(id, request);
        return ResponseEntity.noContent().build();
    }

    // Full replace, not add-one — see StyleTagRequest's comment.
    @PutMapping("/{id}/tags/style")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceStyles(@PathVariable UUID id, @RequestBody StyleTagRequest request) {
        trackService.replaceStyles(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/tags/mood")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceMoods(@PathVariable UUID id, @RequestBody MoodTagRequest request) {
        trackService.replaceMoods(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/tags/context")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceContexts(@PathVariable UUID id, @RequestBody ContextTagRequest request) {
        trackService.replaceContexts(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/tags/rhythm")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceRhythms(@PathVariable UUID id, @RequestBody RhythmTagRequest request) {
        trackService.replaceRhythms(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/tags/instrument")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceFeaturedInstruments(@PathVariable UUID id, @RequestBody FeaturedInstrumentsRequest request) {
        trackService.replaceFeaturedInstruments(id, request);
        return ResponseEntity.noContent().build();
    }

    // Lets the admin tags tool preload what's already tagged before a PUT
    // (full replace) overwrites it — see TrackService.getTrackTags.
    @GetMapping("/{id}/tags")
    public TrackTagsDto getTags(@PathVariable UUID id) {
        return trackService.getTrackTags(id);
    }

    /** Adds this track to the archive's "Featured Tracks" — see {@link TrackService#setFeatured}. */
    @PostMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFeatured(@PathVariable UUID id) {
        trackService.setFeatured(id);
        return ResponseEntity.noContent().build();
    }

    /** Removes this track from "Featured Tracks"*/
    @DeleteMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unsetFeatured(@PathVariable UUID id) {
        trackService.unsetFeatured(id);
        return ResponseEntity.noContent().build();
    }

    /** "Featured Tracks" — see {@link EditorialService#getFeaturedTracks}. */
    @GetMapping("/featured")
    public List<FeaturedTrackDto> featured(@AuthenticationPrincipal Jwt jwt) {
        return editorialService.getFeaturedTracks(currentUserId(jwt));
    }

    /** Marks this track listened for the caller — see {@link ListenService#markTrackListened}. */
    @PostMapping("/{id}/listen")
    public ResponseEntity<Void> markListened(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        listenService.markTrackListened(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    /** Unmarks this track listened for the caller — see {@link ListenService#unmarkTrackListened}. */
    @DeleteMapping("/{id}/listen")
    public ResponseEntity<Void> unmarkListened(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        listenService.unmarkTrackListened(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Creates a note on this track — see {@link NoteService#createNote}.
     *
     * @param id      the track
     * @param request the note's own fields
     * @param jwt     the caller, becomes the note's author
     * @return the created note
     */
    @PostMapping("/{id}/notes")
    public ResponseEntity<NoteDto> createNote(@PathVariable UUID id, @Valid @RequestBody CreateNoteRequest request, @AuthenticationPrincipal Jwt jwt) {
        NoteDto note = noteService.createNote(currentUserId(jwt), id, request.title(), request.text(), request.timestampSeconds());
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    /**
     * A track's whole note feed, paginated — see {@link NoteService#getTrackNotes}.
     * A track's notes are an unbounded community feed, not something safe to
     * return in full.
     *
     * @param id   the track
     * @param jwt  the caller, for "mine first" and each note's likedByCurrentUser
     * @param page 0-based; page size is fixed at {@link #NOTES_PAGE_SIZE}, not client-controlled
     * @return the matching page
     */
    @GetMapping("/{id}/notes")
    public Page<NoteDto> getTrackNotes(
        @PathVariable UUID id,
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") int page
    ) {
        return noteService.getTrackNotes(id, currentUserId(jwt), PageRequest.of(page, NOTES_PAGE_SIZE));
    }

    /**
     * The caller's own notes on this track, paginated — see {@link NoteService#getMyTrackNotes}.
     *
     * @param id   the track
     * @param jwt  the caller
     * @param page 0-based; page size is fixed at {@link #NOTES_PAGE_SIZE}, not client-controlled
     * @return the caller's notes on this track
     */
    @GetMapping("/{id}/notes/me")
    public Page<NoteDto> getMyTrackNotes(
        @PathVariable UUID id,
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") int page
    ) {
        return noteService.getMyTrackNotes(id, currentUserId(jwt), PageRequest.of(page, NOTES_PAGE_SIZE));
    }

    /** Creates or edits the caller's own rating for this track — see {@link TrackRatingService#upsertRating}. */
    @PostMapping("/{id}/ratings")
    public TrackRatingDto upsertRating(@PathVariable UUID id, @Valid @RequestBody CreateTrackRatingRequest request, @AuthenticationPrincipal Jwt jwt) {
        return trackRatingService.upsertRating(currentUserId(jwt), id, request.rating());
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
