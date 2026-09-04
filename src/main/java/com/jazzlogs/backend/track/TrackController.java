package com.jazzlogs.backend.track;

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

import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.TrackEditorial;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.note.NoteService;
import com.jazzlogs.backend.note.dto.CreateNoteRequest;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.track.dto.FeaturedInstrumentsRequest;
import com.jazzlogs.backend.track.dto.PerformerRequest;
import com.jazzlogs.backend.track.dto.RhythmTagRequest;
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

    private final TrackService trackService;
    private final EditorialService editorialService;
    private final ListenService listenService;
    private final NoteService noteService;
    private final TrackRatingService trackRatingService;
    private final UserService userService;

    @PostMapping("/{id}/editorial")
    @PreAuthorize("hasRole('ADMIN')")
    public TrackEditorialDto upsertEditorial(@PathVariable UUID id, @Valid @RequestBody TrackEditorialRequest request) {
        TrackEditorial editorial = editorialService.upsertTrackEditorial(id, request);
        return editorialService.toDto(editorial);
    }

    @PostMapping("/{id}/performers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addPerformer(@PathVariable UUID id, @RequestBody PerformerRequest request) {
        trackService.addPerformer(id, request);
        return ResponseEntity.noContent().build();
    }

    // Full replace, not add-one — see StyleTagRequest's comment.
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

    @PostMapping("/{id}/entry-point/{artistId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> markEntryPoint(@PathVariable UUID id, @PathVariable UUID artistId) {
        trackService.markEntryPoint(id, artistId);
        return ResponseEntity.noContent().build();
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

    @PostMapping("/{id}/listen")
    public ResponseEntity<Void> markListened(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        listenService.markTrackListened(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/listen")
    public ResponseEntity<Void> unmarkListened(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        listenService.unmarkTrackListened(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/notes")
    public ResponseEntity<NoteDto> createNote(@PathVariable UUID id, @Valid @RequestBody CreateNoteRequest request, @AuthenticationPrincipal Jwt jwt) {
        NoteDto note = noteService.createNote(currentUserId(jwt), id, request.title(), request.text(), request.timestampSeconds());
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    // Paged — a track's notes are an unbounded community feed, not something
    // safe to return in full. Defaults match the frontend's page size so an
    // omitted ?size still renders sensibly.
    @GetMapping("/{id}/notes")
    public Page<NoteDto> getTrackNotes(
        @PathVariable UUID id,
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "6") int size
    ) {
        return noteService.getTrackNotes(id, currentUserId(jwt), PageRequest.of(page, size));
    }

    @GetMapping("/{id}/notes/me")
    public List<NoteDto> getMyTrackNotes(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return noteService.getMyTrackNotes(id, currentUserId(jwt));
    }

    @PostMapping("/{id}/ratings")
    public TrackRatingDto upsertRating(@PathVariable UUID id, @Valid @RequestBody CreateTrackRatingRequest request, @AuthenticationPrincipal Jwt jwt) {
        return trackRatingService.upsertRating(currentUserId(jwt), id, request.rating());
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
